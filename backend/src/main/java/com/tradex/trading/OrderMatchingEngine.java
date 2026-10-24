package com.tradex.trading;

import com.tradex.config.TradingProperties;
import com.tradex.entity.Holding;
import com.tradex.entity.Instrument;
import com.tradex.enums.OrderSide;
import com.tradex.enums.OrderStatus;
import com.tradex.enums.OrderType;
import com.tradex.marketdata.MarketDataService;
import com.tradex.marketdata.QuoteSnapshot;
import com.tradex.repository.HoldingRepository;
import com.tradex.repository.OrderRepository;
import com.tradex.service.InstrumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * Continuously works the resting order book.
 *
 * <p>Every tick it re-evaluates each {@code PENDING} limit and stop-loss order against the current
 * price and calls {@link OrderExecutionService#attemptFill(Long)}, which owns the actual fill logic
 * and its locking. Nothing is ever executed merely because it was submitted: a limit buy below the
 * market simply keeps resting.</p>
 *
 * <h2>Attached protective levels</h2>
 * When {@code tradex.trading.auto-trigger-attached-levels} is enabled (default), the engine also
 * watches the stop-loss and target recorded on each open position. If the market trades through one
 * of them, a market order is submitted for the position's free quantity. That is what makes the
 * "stop-loss planning" feature of the risk console behavioural rather than decorative. Each
 * auto-exit is de-duplicated with a time-bucketed idempotency key, so a position can never be
 * liquidated twice by the same trigger.
 */
@Component
@ConditionalOnProperty(name = "tradex.trading.matching-enabled", havingValue = "true", matchIfMissing = true)
public class OrderMatchingEngine {

    private static final Logger log = LoggerFactory.getLogger(OrderMatchingEngine.class);

    private static final DateTimeFormatter BUCKET =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);

    private final OrderRepository orderRepository;
    private final HoldingRepository holdingRepository;
    private final OrderExecutionService executionService;
    private final OrderService orderService;
    private final InstrumentService instrumentService;
    private final MarketDataService marketDataService;
    private final TradingProperties properties;

    public OrderMatchingEngine(OrderRepository orderRepository,
                               HoldingRepository holdingRepository,
                               OrderExecutionService executionService,
                               OrderService orderService,
                               InstrumentService instrumentService,
                               MarketDataService marketDataService,
                               TradingProperties properties) {
        this.orderRepository = orderRepository;
        this.holdingRepository = holdingRepository;
        this.executionService = executionService;
        this.orderService = orderService;
        this.instrumentService = instrumentService;
        this.marketDataService = marketDataService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${tradex.trading.matching-interval-millis:2000}",
            initialDelayString = "${tradex.trading.matching-initial-delay-millis:5000}")
    public void workRestingOrders() {
        List<com.tradex.entity.Order> working = orderRepository.findWorkingOrders(
                Set.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED),
                Set.of(OrderType.LIMIT, OrderType.STOP_LOSS));
        int filled = 0;
        for (com.tradex.entity.Order order : working) {
            try {
                OrderExecutionService.Outcome outcome = executionService.attemptFill(order.getId());
                if (outcome.isFilled()) {
                    filled++;
                    log.info("Matched {} order {} ({} {}) at {}", order.getOrderType(), order.getOrderRef(),
                            order.getQuantity().stripTrailingZeros().toPlainString(),
                            order.getSide(), outcome.order() == null ? "?" : outcome.order().getAvgFillPrice());
                }
            } catch (RuntimeException ex) {
                log.warn("Failed to work order {}: {}", order.getOrderRef(), ex.getMessage());
            }
        }
        if (filled > 0) {
            log.debug("Matching pass filled {} order(s) out of {} working", filled, working.size());
        }
    }

    /**
     * Watches attached stop-loss / target levels on open positions and converts a breach into a
     * market exit. Runs slightly less often than the order book pass.
     */
    @Scheduled(fixedDelayString = "${tradex.trading.matching-interval-millis:2000}",
            initialDelayString = "${tradex.trading.matching-initial-delay-millis:6000}")
    public void workProtectiveLevels() {
        if (!properties.isAutoTriggerAttachedLevels()) {
            return;
        }
        List<Holding> holdings = holdingRepository.findAll();
        for (Holding holding : holdings) {
            try {
                evaluateProtectiveLevels(holding);
            } catch (RuntimeException ex) {
                log.warn("Protective level evaluation failed for holding {}: {}", holding.getId(), ex.getMessage());
            }
        }
    }

    void evaluateProtectiveLevels(Holding holding) {
        if (holding.getQuantity().signum() <= 0) {
            return;
        }
        boolean hasStop = holding.getStopLossPrice() != null;
        boolean hasTarget = holding.getTargetPrice() != null;
        if (!hasStop && !hasTarget) {
            return;
        }
        Instrument instrument = instrumentService.find(holding.getInstrumentId()).orElse(null);
        if (instrument == null || !instrument.isTradable()) {
            return;
        }
        QuoteSnapshot quote = marketDataService.quoteSnapshot(instrument, true);
        BigDecimal price = quote.lastPrice();
        if (price == null) {
            return;
        }

        boolean stopHit = hasStop && price.compareTo(holding.getStopLossPrice()) <= 0;
        boolean targetHit = hasTarget && price.compareTo(holding.getTargetPrice()) >= 0;
        if (!stopHit && !targetHit) {
            return;
        }
        BigDecimal free = executionService.sellableQuantity(
                holding.getAccountId(), holding.getInstrumentId(), null);
        if (free.signum() <= 0) {
            return;
        }

        String bucket = BUCKET.format(Instant.now());
        String key = (stopHit ? "AUTOSL:" : "AUTOTP:") + holding.getId() + ':' + bucket;
        String memo = stopHit
                ? "Protective stop hit at " + holding.getStopLossPrice().stripTrailingZeros().toPlainString()
                : "Profit target hit at " + holding.getTargetPrice().stripTrailingZeros().toPlainString();

        com.tradex.dto.order.PlaceOrderRequest request = new com.tradex.dto.order.PlaceOrderRequest(
                instrument.getSymbol(), OrderSide.SELL, OrderType.MARKET, free,
                null, null, null, null, key);
        try {
            orderService.placeProtectiveExit(holding.getAccountId(), request);
            log.info("Auto-exit for holding {}: {} ({} {} at {})", holding.getId(), memo,
                    free.stripTrailingZeros().toPlainString(), instrument.getSymbol(), price.toPlainString());
        } catch (RuntimeException ex) {
            log.debug("Auto-exit skipped for holding {}: {}", holding.getId(), ex.getMessage());
        }
    }
}

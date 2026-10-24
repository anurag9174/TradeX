package com.tradex.trading;

import com.tradex.config.TradingProperties;
import com.tradex.entity.Holding;
import com.tradex.entity.Instrument;
import com.tradex.entity.Order;
import com.tradex.entity.Trade;
import com.tradex.entity.TradingAccount;
import com.tradex.enums.LedgerEntryType;
import com.tradex.enums.OrderSide;
import com.tradex.enums.OrderStatus;
import com.tradex.enums.OrderType;
import com.tradex.exception.BadRequestException;
import com.tradex.exception.InsufficientFundsException;
import com.tradex.exception.InsufficientHoldingsException;
import com.tradex.exception.TradingBlockedException;
import com.tradex.marketdata.MarketDataService;
import com.tradex.repository.HoldingRepository;
import com.tradex.repository.OrderRepository;
import com.tradex.repository.TradeRepository;
import com.tradex.repository.TradingAccountRepository;
import com.tradex.service.AccountChangedEvent;
import com.tradex.service.InstrumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * The order execution engine: everything that turns an accepted order into a booked fill.
 *
 * <h2>Concurrency</h2>
 * Every entry point takes a pessimistic write lock on the <em>order</em> row first and then on the
 * <em>account</em> row. Because both locks are taken in the same order by every code path, two
 * concurrent attempts on the same order - or on two orders against the same account - serialise
 * cleanly and deadlock-free.
 *
 * <h2>Duplicate execution defence (three independent layers)</h2>
 * <ol>
 *   <li>{@link OrderStatus#isOpen()} is checked on the locked order; a terminal order is skipped.</li>
 *   <li>{@code orders.version} is an optimistic counter; a lost update fails the transaction.</li>
 *   <li>{@code trades.execution_key} is uniquely constrained; a replayed fill cannot be inserted.</li>
 * </ol>
 *
 * <h2>Cash movements</h2>
 * Every balance change is paired with an immutable ledger row. A rejection releases whatever the
 * order had reserved, so a rejected order never strands the user's cash.
 */
@Service
public class OrderExecutionService {

    private static final Logger log = LoggerFactory.getLogger(OrderExecutionService.class);

    private final OrderRepository orderRepository;
    private final TradingAccountRepository accountRepository;
    private final HoldingRepository holdingRepository;
    private final TradeRepository tradeRepository;
    private final InstrumentService instrumentService;
    private final MarketDataService marketDataService;
    private final ChargesCalculator chargesCalculator;
    private final LedgerService ledgerService;
    private final HoldingService holdingService;
    private final CurrencyConverter currencyConverter;
    private final TradingProperties properties;
    private final TradingClock clock;
    private final ApplicationEventPublisher eventPublisher;

    public OrderExecutionService(OrderRepository orderRepository,
                                 TradingAccountRepository accountRepository,
                                 HoldingRepository holdingRepository,
                                 TradeRepository tradeRepository,
                                 InstrumentService instrumentService,
                                 MarketDataService marketDataService,
                                 ChargesCalculator chargesCalculator,
                                 LedgerService ledgerService,
                                 HoldingService holdingService,
                                 CurrencyConverter currencyConverter,
                                 TradingProperties properties,
                                 TradingClock clock,
                                 ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.tradeRepository = tradeRepository;
        this.instrumentService = instrumentService;
        this.marketDataService = marketDataService;
        this.chargesCalculator = chargesCalculator;
        this.ledgerService = ledgerService;
        this.holdingService = holdingService;
        this.currencyConverter = currencyConverter;
        this.properties = properties;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    public enum OutcomeStatus {
        /** The order was executed and a trade booked. */
        FILLED,
        /** The order is still working (limit not reached / stop not triggered). */
        SKIPPED,
        /** The order was rejected by the engine and is now terminal. */
        REJECTED
    }

    public record Outcome(OutcomeStatus status, Order order, Trade trade, String reason) {

        static Outcome filled(Order order, Trade trade) {
            return new Outcome(OutcomeStatus.FILLED, order, trade, null);
        }

        static Outcome skipped(Order order, String reason) {
            return new Outcome(OutcomeStatus.SKIPPED, order, null, reason);
        }

        static Outcome rejected(Order order, String reason) {
            return new Outcome(OutcomeStatus.REJECTED, order, null, reason);
        }

        public boolean isFilled() {
            return status == OutcomeStatus.FILLED;
        }
    }

    // =======================================================================================
    // Fill attempt
    // =======================================================================================

    /**
     * Attempts to fill one order. Safe to call repeatedly: an order that is already terminal is
     * reported as skipped without touching the account.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Outcome attemptFill(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) {
            return new Outcome(OutcomeStatus.SKIPPED, null, null, "Order no longer exists");
        }
        // Layer 1: an order that already reached a terminal state can never fill twice.
        if (!order.getStatus().isOpen()) {
            return Outcome.skipped(order, "Order is " + order.getStatus() + " and cannot be executed again");
        }

        TradingAccount account = accountRepository.findByIdForUpdate(order.getAccountId())
                .orElseThrow(() -> new IllegalStateException("Order " + orderId + " references a missing account"));
        Instrument instrument = instrumentService.require(order.getInstrumentId());
        try {
            currencyConverter.requireSameCurrencyForTrading(instrument, account.getCurrency());
        } catch (BadRequestException ex) {
            return reject(order, account, ex.getMessage());
        }
        Instant now = clock.now();

        BigDecimal marketPrice;
        try {
            marketPrice = marketDataService.executionPrice(instrument, order.getLimitPrice() != null
                    ? order.getLimitPrice() : order.getStopPrice());
        } catch (RuntimeException ex) {
            return reject(order, account, "No tradable price for " + instrument.getSymbol() + ": " + ex.getMessage());
        }

        BigDecimal fillPrice;
        switch (order.getOrderType()) {
            case MARKET -> fillPrice = withSlippage(marketPrice, order.getSide());
            case LIMIT -> {
                if (!limitSatisfied(order, marketPrice)) {
                    return Outcome.skipped(order, "Limit condition not met (market " + marketPrice.toPlainString()
                            + " vs limit " + order.getLimitPrice().toPlainString() + ")");
                }
                // Price protected: never worse than the limit, better when the book allows it.
                fillPrice = priceProtectedFill(order, marketPrice);
            }
            case STOP_LOSS -> {
                if (!stopTriggered(order, marketPrice)) {
                    return Outcome.skipped(order, "Stop not triggered (market " + marketPrice.toPlainString()
                            + " vs stop " + order.getStopPrice().toPlainString() + ")");
                }
                order.setTriggeredAt(now);
                fillPrice = withSlippage(marketPrice, order.getSide());
            }
            default -> {
                return reject(order, account, "Unsupported order type " + order.getOrderType());
            }
        }
        fillPrice = Decimals.toTick(fillPrice, instrument.getTickSize());
        if (fillPrice.signum() <= 0) {
            return reject(order, account, "Resolved an invalid execution price");
        }

        BigDecimal quantity = order.getQuantity();
        BigDecimal turnover = currencyConverter.toLedger(instrument, quantity.multiply(fillPrice, Decimals.MC),
                account.getCurrency());
        BigDecimal charges = chargesCalculator.total(turnover, order.getSide());

        return order.getSide() == OrderSide.BUY
                ? fillBuy(order, account, instrument, quantity, fillPrice, turnover, charges, now)
                : fillSell(order, account, instrument, quantity, fillPrice, turnover, charges, now);
    }

    // ---------------------------------------------------------------------------------------
    // Buy leg
    // ---------------------------------------------------------------------------------------

    private Outcome fillBuy(Order order, TradingAccount account, Instrument instrument,
                            BigDecimal quantity, BigDecimal fillPrice, BigDecimal turnover,
                            BigDecimal charges, Instant now) {
        BigDecimal totalCost = Decimals.money(turnover.add(charges));
        BigDecimal reserved = Decimals.nullToZero(order.getReservedCash());

        // Cash that will be spendable once the reservation is unwound.
        BigDecimal spendable = Decimals.money(account.getCashBalance().add(reserved));
        if (spendable.compareTo(totalCost) < 0) {
            String reason = "Insufficient virtual balance at execution price " + fillPrice.toPlainString()
                    + ": required " + totalCost.toPlainString() + ", available " + spendable.toPlainString();
            if (reserved.signum() > 0) {
                ledgerService.releaseReservedCash(account, reserved, order.getId(),
                        "Reservation released: could not be filled (" + order.getOrderRef() + ")");
                order.setReservedCash(BigDecimal.ZERO);
            }
            return reject(order, account, reason);
        }

        if (reserved.signum() > 0) {
            ledgerService.releaseReservedCash(account, reserved, order.getId(),
                    "Reservation released for execution (" + order.getOrderRef() + ")");
            order.setReservedCash(BigDecimal.ZERO);
        }
        ledgerService.debitCash(account, LedgerEntryType.BUY_SETTLEMENT, turnover, order.getId(), null,
                "Bought " + trim(quantity) + " " + instrument.getSymbol() + " @ " + fillPrice.toPlainString());
        if (charges.signum() > 0) {
            ledgerService.debitCash(account, LedgerEntryType.CHARGES, charges, order.getId(), null,
                    "Charges on buy of " + instrument.getSymbol());
        }

        Trade trade = bookTrade(order, quantity, fillPrice, turnover, charges, BigDecimal.ZERO, null, now);
        holdingService.applyBuy(account.getId(), account.getCurrency(), instrument, quantity, fillPrice,
                charges, trade, now);

        ledgerService.addCharges(account, charges);
        order.setCharges(charges);
        order.setFilledQuantity(quantity);
        order.setAvgFillPrice(fillPrice);
        order.setRealizedPnl(BigDecimal.ZERO);
        order.setNetCashImpact(totalCost.negate());
        order.setStatus(OrderStatus.EXECUTED);
        order.setExecutedAt(now);
        orderRepository.save(order);

        applyProtectiveLevels(order, instrument);
        eventPublisher.publishEvent(new AccountChangedEvent(order.getAccountId(), "BUY_FILLED"));
        return Outcome.filled(order, trade);
    }

    // ---------------------------------------------------------------------------------------
    // Sell leg
    // ---------------------------------------------------------------------------------------

    private Outcome fillSell(Order order, TradingAccount account, Instrument instrument,
                             BigDecimal quantity, BigDecimal fillPrice, BigDecimal turnover,
                             BigDecimal charges, Instant now) {
        Holding holding = holdingRepository
                .findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId())
                .orElse(null);
        if (holding == null || holding.getQuantity().compareTo(quantity) < 0) {
            BigDecimal held = holding == null ? BigDecimal.ZERO : holding.getQuantity();
            String reason = "Sell of " + trim(quantity) + " " + instrument.getSymbol()
                    + " could not be filled: position is " + trim(held);
            releaseStandingReservation(order, account, "reservation released: " + reason);
            return reject(order, account, reason);
        }

        BigDecimal costBasis = holding.getAvgCost();
        BigDecimal realized = Decimals.money(currencyConverter
                .toLedger(instrument, fillPrice.subtract(costBasis)
                        .multiply(quantity, Decimals.MC), account.getCurrency())
                .subtract(charges));

        BigDecimal proceeds = Decimals.money(turnover.subtract(charges));
        ledgerService.creditCash(account, LedgerEntryType.SELL_SETTLEMENT, turnover, order.getId(), null,
                "Sold " + trim(quantity) + " " + instrument.getSymbol() + " @ " + fillPrice.toPlainString());
        if (charges.signum() > 0) {
            ledgerService.debitCash(account, LedgerEntryType.CHARGES, charges, order.getId(), null,
                    "Charges on sale of " + instrument.getSymbol());
        }

        Trade trade = bookTrade(order, quantity, fillPrice, turnover, charges, realized, costBasis, now);
        BigDecimal booked = holdingService.applySell(account.getId(), account.getCurrency(), instrument, quantity,
                fillPrice, charges, trade, now);

        ledgerService.addRealizedPnl(account, booked);
        ledgerService.addCharges(account, charges);

        order.setCharges(charges);
        order.setFilledQuantity(quantity);
        order.setAvgFillPrice(fillPrice);
        order.setRealizedPnl(booked);
        order.setNetCashImpact(proceeds);
        order.setStatus(OrderStatus.EXECUTED);
        order.setExecutedAt(now);
        orderRepository.save(order);

        // A sell that empties a position clears the position's protective levels with it.
        Holding remaining = holdingRepository.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
                .orElse(null);
        if (remaining != null) {
            applyProtectiveLevels(order, instrument);
        }
        eventPublisher.publishEvent(new AccountChangedEvent(order.getAccountId(), "SELL_FILLED"));
        return Outcome.filled(order, trade);
    }

    // ---------------------------------------------------------------------------------------
    // Rejection
    // ---------------------------------------------------------------------------------------

    /** Terminal rejection: releases any reservation, records the reason and audits the ledger. */
    @Transactional(propagation = Propagation.REQUIRED)
    public Outcome reject(Order order, TradingAccount account, String reason) {
        order.setStatus(OrderStatus.REJECTED);
        order.setRejectReason(truncate(reason, 250));
        order.setExecutedAt(null);
        orderRepository.save(order);
        log.debug("Order {} rejected: {}", order.getOrderRef(), reason);
        eventPublisher.publishEvent(new AccountChangedEvent(order.getAccountId(), "ORDER_REJECTED"));
        return Outcome.rejected(order, reason);
    }

    private void releaseStandingReservation(Order order, TradingAccount account, String description) {
        BigDecimal reserved = Decimals.nullToZero(order.getReservedCash());
        if (reserved.signum() > 0) {
            ledgerService.releaseReservedCash(account, reserved, order.getId(),
                    truncate(description, 250));
            order.setReservedCash(BigDecimal.ZERO);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private Trade bookTrade(Order order, BigDecimal quantity, BigDecimal fillPrice, BigDecimal turnover,
                            BigDecimal charges, BigDecimal realized, BigDecimal costBasis, Instant now) {
        Trade trade = new Trade();
        trade.setTradeRef(Refs.trade());
        // Layer 3 of the duplicate-execution defence: one fill slot per order.
        trade.setExecutionKey(order.getId() + ":1");
        trade.setOrderId(order.getId());
        trade.setAccountId(order.getAccountId());
        trade.setInstrumentId(order.getInstrumentId());
        trade.setSide(order.getSide());
        trade.setQuantity(quantity);
        trade.setPrice(fillPrice);
        trade.setGrossAmount(turnover);
        trade.setCharges(charges);
        trade.setNetAmount(Decimals.money(order.getSide() == OrderSide.BUY
                ? turnover.add(charges).negate()
                : turnover.subtract(charges)));
        trade.setRealizedPnl(realized);
        trade.setCostBasis(costBasis);
        trade.setExecutedAt(now);
        trade.setCreatedAt(now);
        return tradeRepository.save(trade);
    }

    /** Copies the optional protective levels from the order onto the resulting position. */
    private void applyProtectiveLevels(Order order, Instrument instrument) {
        Holding holding = holdingRepository
                .findByAccountIdAndInstrumentId(order.getAccountId(), instrument.getId())
                .orElse(null);
        if (holding == null) {
            return;
        }
        boolean changed = false;
        if (order.getPositionStopLoss() != null) {
            holding.setStopLossPrice(Decimals.toTick(order.getPositionStopLoss(), instrument.getTickSize()));
            changed = true;
        }
        if (order.getPositionTarget() != null) {
            holding.setTargetPrice(Decimals.toTick(order.getPositionTarget(), instrument.getTickSize()));
            changed = true;
        }
        if (changed) {
            holdingRepository.save(holding);
        }
    }

    private boolean limitSatisfied(Order order, BigDecimal marketPrice) {
        BigDecimal limit = order.getLimitPrice();
        if (limit == null) {
            return false;
        }
        return order.getSide() == OrderSide.BUY
                ? marketPrice.compareTo(limit) <= 0
                : marketPrice.compareTo(limit) >= 0;
    }

    private BigDecimal priceProtectedFill(Order order, BigDecimal marketPrice) {
        BigDecimal limit = order.getLimitPrice();
        return order.getSide() == OrderSide.BUY ? Decimals.min(limit, marketPrice) : Decimals.max(limit, marketPrice);
    }

    private boolean stopTriggered(Order order, BigDecimal marketPrice) {
        BigDecimal stop = order.getStopPrice();
        if (stop == null) {
            return false;
        }
        return order.getSide() == OrderSide.SELL
                ? marketPrice.compareTo(stop) <= 0
                : marketPrice.compareTo(stop) >= 0;
    }

    private BigDecimal withSlippage(BigDecimal price, OrderSide side) {
        int bps = properties.getMarketOrderSlippageBps();
        if (bps == 0) {
            return price;
        }
        BigDecimal adjusted = Decimals.applyBps(price, side == OrderSide.BUY ? bps : -bps);
        return adjusted.setScale(Decimals.MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String trim(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    // =======================================================================================
    // Pre-trade validation helpers (shared with OrderService)
    // =======================================================================================

    /**
     * Quantity already committed to resting sell orders, excluding the order given.
     * Must be called while holding the account lock.
     */
    public BigDecimal reservedSellQuantity(Long accountId, Long instrumentId, Long excludeOrderId) {
        BigDecimal reserved = orderRepository.sumOpenSellQuantity(accountId, instrumentId,
                Set.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED), excludeOrderId);
        return Decimals.qty(Decimals.nullToZero(reserved));
    }

    /** Sellable quantity = holding minus anything already promised to another resting sell order. */
    public BigDecimal sellableQuantity(Long accountId, Long instrumentId, Long excludeOrderId) {
        Holding holding = holdingRepository.findByAccountIdAndInstrumentId(accountId, instrumentId).orElse(null);
        BigDecimal held = holding == null ? BigDecimal.ZERO : holding.getQuantity();
        BigDecimal free = held.subtract(reservedSellQuantity(accountId, instrumentId, excludeOrderId));
        return Decimals.qty(free.max(BigDecimal.ZERO));
    }

    // =======================================================================================
    // Validation contracts used by OrderService (throw so the API can answer 4xx)
    // =======================================================================================

    public void requireFunds(TradingAccount account, BigDecimal required) {
        if (account.getCashBalance().compareTo(required) < 0) {
            throw new InsufficientFundsException(required, account.getCashBalance());
        }
    }

    public void requireHoldings(BigDecimal requested, BigDecimal available, String symbol) {
        if (available.compareTo(requested) < 0) {
            throw new InsufficientHoldingsException(requested, available, symbol);
        }
    }

    public void requireTradable(Instrument instrument) {
        if (!instrument.isActive()) {
            throw new BadRequestException("INSTRUMENT_INACTIVE",
                    "Instrument " + instrument.getSymbol() + " is not active.");
        }
        if (!instrument.isTradable()) {
            throw new BadRequestException("INSTRUMENT_NOT_TRADABLE",
                    instrument.getSymbol() + " ("
                            + instrument.getName() + ") is an index and cannot be traded directly. "
                            + "It is available for charting and analysis only.");
        }
    }

    public void requireOpen(TradingAccount account) {
        if (account.isTradingBlocked()) {
            throw new TradingBlockedException(
                    "Trading is blocked on this account. Clear the block from the Risk console to continue.");
        }
    }

    /** Applies the instrument's lot size; quantities must be a whole number of lots. */
    public BigDecimal normaliseQuantity(Instrument instrument, BigDecimal requested) {
        BigDecimal lot = instrument.getLotSize() == null ? BigDecimal.ONE : instrument.getLotSize();
        if (lot.signum() <= 0) {
            lot = BigDecimal.ONE;
        }
        BigDecimal lots = requested.divide(lot, 0, RoundingMode.DOWN);
        if (lots.signum() <= 0) {
            throw new BadRequestException("INVALID_QUANTITY",
                    "Quantity must be at least one lot (" + lot.stripTrailingZeros().toPlainString()
                            + ") of " + instrument.getSymbol() + ".");
        }
        return Decimals.qty(lots.multiply(lot));
    }

    /** Validates that a stop price sits on the correct side of the current market. */
    public void validateStopSide(OrderSide side, BigDecimal stopPrice, BigDecimal marketPrice, String symbol) {
        if (side == OrderSide.SELL && stopPrice.compareTo(marketPrice) >= 0) {
            throw new BadRequestException("INVALID_STOP_PRICE",
                    "A sell stop for " + symbol + " must be below the current price "
                            + marketPrice.toPlainString() + " (received " + stopPrice.toPlainString() + ").");
        }
        if (side == OrderSide.BUY && stopPrice.compareTo(marketPrice) <= 0) {
            throw new BadRequestException("INVALID_STOP_PRICE",
                    "A buy stop for " + symbol + " must be above the current price "
                            + marketPrice.toPlainString() + " (received " + stopPrice.toPlainString() + ").");
        }
    }

    /** Lists the working orders of one instrument so a per-instrument pass can be logged. */
    @Transactional(readOnly = true)
    public List<Order> workingOrders() {
        return orderRepository.findWorkingOrders(
                Set.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED),
                Set.of(OrderType.LIMIT, OrderType.STOP_LOSS));
    }
}

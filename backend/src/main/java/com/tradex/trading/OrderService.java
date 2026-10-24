package com.tradex.trading;

import com.tradex.dto.order.ChargesBreakdownDto;
import com.tradex.dto.order.OrderActionResponse;
import com.tradex.dto.order.OrderDto;
import com.tradex.dto.order.OrderEstimateDto;
import com.tradex.dto.order.OrderEstimateRequest;
import com.tradex.dto.order.PlaceOrderRequest;
import com.tradex.config.TradingProperties;
import com.tradex.dto.common.PageResponse;
import com.tradex.entity.Instrument;
import com.tradex.entity.Order;
import com.tradex.entity.TradingAccount;
import com.tradex.enums.OrderSide;
import com.tradex.enums.OrderStatus;
import com.tradex.enums.OrderType;
import com.tradex.exception.BadRequestException;
import com.tradex.exception.ConflictException;
import com.tradex.exception.ForbiddenException;
import com.tradex.exception.ResourceNotFoundException;
import com.tradex.marketdata.MarketDataService;
import com.tradex.marketdata.QuoteSnapshot;
import com.tradex.repository.OrderRepository;
import com.tradex.repository.TradingAccountRepository;
import com.tradex.service.AccountChangedEvent;
import com.tradex.service.InstrumentService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Order intake, validation, reservation and lifecycle management.
 *
 * <h2>Why nothing is trusted from the client</h2>
 * {@link PlaceOrderRequest} carries an instrument, a direction, a type and a quantity - and nothing
 * else that feeds a calculation. Price, order value, charges, balance and P&amp;L are all derived
 * server-side at placement and again at execution. A tampered client can therefore only ask for
 * something, never assert an outcome.
 *
 * <h2>Cash reservation</h2>
 * A resting BUY order immediately moves {@code quantity * price + charges} out of spendable cash and
 * into {@code reservedCash} through an immutable ledger entry. The user can see the reservation in
 * the portfolio view, it is released in full on cancel/reject, and it is consumed on execution. That
 * is what prevents the same rupee from funding two open orders.
 */
@Service
public class OrderService {

    /** Statuses shown under each Orders-page tab. */
    private static final Map<String, List<OrderStatus>> TABS = new LinkedHashMap<>();

    static {
        TABS.put("all", List.of(OrderStatus.values()));
        TABS.put("open", List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED));
        TABS.put("executed", List.of(OrderStatus.EXECUTED));
        TABS.put("cancelled", List.of(OrderStatus.CANCELLED));
        TABS.put("rejected", List.of(OrderStatus.REJECTED));
        TABS.put("expired", List.of(OrderStatus.EXPIRED));
    }

    private final OrderRepository orderRepository;
    private final TradingAccountRepository accountRepository;
    private final InstrumentService instrumentService;
    private final MarketDataService marketDataService;
    private final ChargesCalculator chargesCalculator;
    private final OrderExecutionService executionService;
    private final RiskGuardService riskGuardService;
    private final PortfolioService portfolioService;
    private final LedgerService ledgerService;
    private final CurrencyConverter currencyConverter;
    private final TradingProperties tradingProperties;
    private final ApplicationEventPublisher eventPublisher;


    public OrderService(OrderRepository orderRepository,
                        TradingAccountRepository accountRepository,
                        InstrumentService instrumentService,
                        MarketDataService marketDataService,
                        ChargesCalculator chargesCalculator,
                        OrderExecutionService executionService,
                        RiskGuardService riskGuardService,
                        PortfolioService portfolioService,
                        LedgerService ledgerService,
                        CurrencyConverter currencyConverter,
                        TradingProperties tradingProperties,
                        ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.accountRepository = accountRepository;
        this.instrumentService = instrumentService;
        this.marketDataService = marketDataService;
        this.chargesCalculator = chargesCalculator;
        this.executionService = executionService;
        this.riskGuardService = riskGuardService;
        this.portfolioService = portfolioService;
        this.ledgerService = ledgerService;
        this.currencyConverter = currencyConverter;
        this.tradingProperties = tradingProperties;
        this.eventPublisher = eventPublisher;
    }

    // =======================================================================================
    // Placement
    // =======================================================================================

    @Transactional
    public OrderActionResponse placeOrder(Long userId, PlaceOrderRequest request) {
        // Single serialisation point: the account row lock orders every concurrent request.
        TradingAccount account = accountRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("ACCOUNT_NOT_FOUND",
                        "No trading account exists for this user."));
        return placeOnAccount(account, request, false);
    }

    /**
     * System-initiated placement (protective exits from the matching engine).
     *
     * @param protectiveExit when true the daily-loss guard is bypassed, because closing an existing
     *                       position reduces risk rather than adding to it
     */
    @Transactional
    public OrderActionResponse placeProtectiveExit(Long accountId, PlaceOrderRequest request) {
        TradingAccount account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("ACCOUNT_NOT_FOUND",
                        "No trading account exists for account " + accountId + "."));
        boolean bypassGuard = properties().isAllowProtectiveExitsWhenBlocked();
        return placeOnAccount(account, request, bypassGuard);
    }

    private com.tradex.config.TradingProperties properties() {
        return tradingProperties;
    }

    private OrderActionResponse placeOnAccount(TradingAccount account, PlaceOrderRequest request,
                                               boolean bypassRiskGuard) {
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            Optional<Order> existing = orderRepository
                    .findByAccountIdAndIdempotencyKey(account.getId(), request.idempotencyKey().trim());
            if (existing.isPresent()) {
                Order replay = existing.get();
                return OrderActionResponse.of(toDto(replay, account.getCurrency()),
                        "Duplicate submission ignored; returning the original order " + replay.getOrderRef() + ".",
                        account.getCashBalance(), account.getReservedCash());
            }
        }

        Instrument instrument = instrumentService.require(request.symbol());
        executionService.requireTradable(instrument);
        currencyConverter.requireSameCurrencyForTrading(instrument, account.getCurrency());

        BigDecimal quantity = executionService.normaliseQuantity(instrument, request.quantity());

        // Risk guard first: a blocked account should not even be told what it can afford.
        BigDecimal todayNetPnl = portfolioService.todaysNetPnl(account);
        if (!bypassRiskGuard) {
            riskGuardService.enforceCanTrade(account, todayNetPnl);
        }

        BigDecimal chargesEstimate;
        BigDecimal reserved = BigDecimal.ZERO;
        BigDecimal limitPrice = null;
        BigDecimal stopPrice = null;

        switch (request.orderType()) {
            case MARKET -> chargesEstimate = estimateCharges(instrument, request.side(), quantity, null,
                    account.getCurrency());
            case LIMIT -> {
                if (request.limitPrice() == null || request.limitPrice().signum() <= 0) {
                    throw new BadRequestException("LIMIT_PRICE_REQUIRED",
                            "A limit order requires a positive limit price.");
                }
                limitPrice = Decimals.toTick(request.limitPrice(), instrument.getTickSize());
                chargesEstimate = estimateCharges(instrument, request.side(), quantity, limitPrice,
                        account.getCurrency());
                if (request.side() == OrderSide.BUY) {
                    reserved = Decimals.money(currencyConverter
                            .toLedger(instrument, limitPrice.multiply(quantity, Decimals.MC), account.getCurrency())
                            .add(chargesEstimate));
                }
            }
            case STOP_LOSS -> {
                if (request.stopPrice() == null || request.stopPrice().signum() <= 0) {
                    throw new BadRequestException("STOP_PRICE_REQUIRED",
                            "A stop-loss order requires a positive trigger price.");
                }
                stopPrice = Decimals.toTick(request.stopPrice(), instrument.getTickSize());
                BigDecimal reference = marketDataService.executionPrice(instrument, null);
                executionService.validateStopSide(request.side(), stopPrice, reference, instrument.getSymbol());
                chargesEstimate = estimateCharges(instrument, request.side(), quantity, stopPrice,
                        account.getCurrency());
                if (request.side() == OrderSide.BUY) {
                    reserved = Decimals.money(currencyConverter
                            .toLedger(instrument, stopPrice.multiply(quantity, Decimals.MC), account.getCurrency())
                            .add(chargesEstimate));
                }
            }
            default -> throw new BadRequestException("Unsupported order type " + request.orderType());
        }

        if (request.side() == OrderSide.SELL) {
            BigDecimal sellable = executionService.sellableQuantity(account.getId(), instrument.getId(), null);
            executionService.requireHoldings(quantity, sellable, instrument.getSymbol());
        } else if (reserved.signum() > 0) {
            executionService.requireFunds(account, reserved);
        }

        Order order = new Order();
        order.setOrderRef(Refs.order());
        order.setAccountId(account.getId());
        order.setInstrumentId(instrument.getId());
        order.setSide(request.side());
        order.setOrderType(request.orderType());
        order.setStatus(OrderStatus.PENDING);
        order.setQuantity(quantity);
        order.setLimitPrice(limitPrice);
        order.setStopPrice(stopPrice);
        order.setPositionStopLoss(request.positionStopLoss() == null ? null
                : Decimals.toTick(request.positionStopLoss(), instrument.getTickSize()));
        order.setPositionTarget(request.positionTarget() == null ? null
                : Decimals.toTick(request.positionTarget(), instrument.getTickSize()));
        order.setFilledQuantity(BigDecimal.ZERO);
        order.setEstimatedCharges(chargesEstimate);
        order.setReservedCash(BigDecimal.ZERO);
        order.setIdempotencyKey(request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                ? null : request.idempotencyKey().trim());
        order.setCreatedAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        order = orderRepository.save(order);

        if (reserved.signum() > 0) {
            ledgerService.reserveCash(account, reserved, order.getId(),
                    "Cash reserved for " + order.getOrderRef() + " (" + instrument.getSymbol() + ")");
            order.setReservedCash(reserved);
            order = orderRepository.save(order);
        }

        // Market orders always fill. Limit and stop orders only fill when their condition holds -
        // a limit buy below the market rests on the book rather than executing on submission.
        OrderExecutionService.Outcome outcome = executionService.attemptFill(order.getId());
        Order reloaded = orderRepository.findById(order.getId()).orElse(order);
        TradingAccount freshAccount = accountRepository.findById(account.getId()).orElse(account);

        String message = describe(outcome, reloaded, instrument);
        eventPublisher.publishEvent(new AccountChangedEvent(account.getId(), "ORDER_" + outcome.status()));
        return OrderActionResponse.of(toDto(reloaded, freshAccount.getCurrency()), message,
                freshAccount.getCashBalance(), freshAccount.getReservedCash());
    }

    private String describe(OrderExecutionService.Outcome outcome, Order order, Instrument instrument) {
        return switch (outcome.status()) {
            case FILLED -> (order.getSide() == OrderSide.BUY ? "Bought " : "Sold ")
                    + trim(order.getFilledQuantity()) + " " + instrument.getSymbol()
                    + " at " + trim(order.getAvgFillPrice()) + ". Charges "
                    + trim(order.getCharges()) + ".";
            case REJECTED -> "Order rejected: " + outcome.reason();
            case SKIPPED -> order.getOrderType() == OrderType.STOP_LOSS
                    ? "Stop-loss order accepted and watching " + instrument.getSymbol()
                    + ". It will execute when the market reaches " + trim(order.getStopPrice()) + "."
                    : "Limit order accepted and resting at " + trim(order.getLimitPrice())
                    + ". It will execute when " + instrument.getSymbol() + " trades through your limit.";
        };
    }

    private BigDecimal estimateCharges(Instrument instrument, OrderSide side, BigDecimal quantity, BigDecimal price,
                                       String accountCurrency) {
        BigDecimal reference = price;
        if (reference == null) {
            QuoteSnapshot snapshot = marketDataService.quoteSnapshot(instrument);
            reference = snapshot.lastPrice();
        }
        if (reference == null) {
            reference = instrument.getSeedPrice();
        }
        return chargesCalculator.total(
                currencyConverter.toLedger(instrument, quantity.multiply(reference, Decimals.MC), accountCurrency), side);
    }

    // =======================================================================================
    // Estimation
    // =======================================================================================

    @Transactional(readOnly = true)
    public OrderEstimateDto estimate(Long userId, OrderEstimateRequest request) {
        TradingAccount account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("ACCOUNT_NOT_FOUND",
                        "No trading account exists for this user."));
        Instrument instrument = instrumentService.require(request.symbol());
        currencyConverter.requireSameCurrencyForTrading(instrument, account.getCurrency());
        BigDecimal quantity = executionService.normaliseQuantity(instrument, request.quantity());
        QuoteSnapshot snapshot = marketDataService.quoteSnapshot(instrument);
        BigDecimal reference = snapshot.lastPrice() == null ? instrument.getSeedPrice() : snapshot.lastPrice();

        BigDecimal assumed = switch (request.orderType()) {
            case MARKET -> reference;
            case LIMIT -> request.limitPrice() == null ? reference
                    : Decimals.toTick(request.limitPrice(), instrument.getTickSize());
            case STOP_LOSS -> request.stopPrice() == null ? reference
                    : Decimals.toTick(request.stopPrice(), instrument.getTickSize());
        };

        BigDecimal orderValue = currencyConverter.toLedger(instrument, quantity.multiply(assumed, Decimals.MC),
                account.getCurrency());
        ChargesBreakdownDto charges = chargesCalculator.calculate(orderValue, request.side());
        BigDecimal totalCost = Decimals.money(orderValue.add(charges.total()));
        BigDecimal netProceeds = Decimals.money(orderValue.subtract(charges.total()));

        BigDecimal held = BigDecimal.ZERO;
        if (request.side() == OrderSide.SELL) {
            held = executionService.sellableQuantity(account.getId(), instrument.getId(), null);
        }

        boolean sufficientFunds = request.side() != OrderSide.BUY
                || account.getCashBalance().compareTo(totalCost) >= 0;
        BigDecimal shortfall = sufficientFunds ? BigDecimal.ZERO
                : Decimals.money(totalCost.subtract(account.getCashBalance()));
        boolean sufficientHoldings = request.side() != OrderSide.SELL || held.compareTo(quantity) >= 0;

        List<String> warnings = new ArrayList<>();
        if (!instrument.isTradable()) {
            warnings.add(instrument.getSymbol() + " is an index and cannot be traded directly.");
        }
        if (request.orderType() == OrderType.MARKET) {
            warnings.add("Market orders fill at the prevailing quoted price, which may differ from the "
                    + "price shown here by the time the order is processed.");
        }
        if (request.orderType() == OrderType.LIMIT) {
            warnings.add("A limit order rests until the market reaches your price. It never executes "
                    + "merely because it was submitted.");
        }
        if (!sufficientFunds) {
            warnings.add("This order exceeds your available virtual balance by " + trim(shortfall) + ".");
        }
        if (!sufficientHoldings) {
            warnings.add("You hold " + trim(held) + " " + instrument.getSymbol()
                    + ", which is less than the requested quantity.");
        }

        return new OrderEstimateDto(
                instrument.getSymbol(),
                request.side().name(),
                request.orderType().name(),
                quantity,
                reference,
                assumed,
                orderValue,
                charges,
                totalCost,
                netProceeds,
                account.getCashBalance(),
                account.getReservedCash(),
                sufficientFunds,
                shortfall,
                held,
                sufficientHoldings,
                warnings,
                instrument.getCurrency(),
                account.getCurrency(),
                currencyConverter.factor(instrument, account.getCurrency()));
    }

    // =======================================================================================
    // Cancellation
    // =======================================================================================

    @Transactional
    public OrderDto cancelOrder(Long userId, Long orderId) {
        TradingAccount account = accountRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("ACCOUNT_NOT_FOUND",
                        "No trading account exists for this user."));
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("ORDER_NOT_FOUND", "Order " + orderId + " was not found."));

        // Account isolation: an order that belongs to somebody else is reported as missing, not forbidden.
        if (!order.getAccountId().equals(account.getId())) {
            throw new ResourceNotFoundException("ORDER_NOT_FOUND", "Order " + orderId + " was not found.");
        }
        if (!order.getStatus().isOpen()) {
            throw new ConflictException("ORDER_NOT_CANCELLABLE",
                    "Order " + order.getOrderRef() + " is " + order.getStatus() + " and can no longer be cancelled.");
        }

        BigDecimal reserved = Decimals.nullToZero(order.getReservedCash());
        if (reserved.signum() > 0) {
            ledgerService.releaseReservedCash(account, reserved, order.getId(),
                    "Reservation released on cancellation of " + order.getOrderRef());
            order.setReservedCash(BigDecimal.ZERO);
        }
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelledAt(Instant.now());
        orderRepository.save(order);
        eventPublisher.publishEvent(new AccountChangedEvent(account.getId(), "ORDER_CANCELLED"));
        return toDto(order);
    }

    // =======================================================================================
    // Queries
    // =======================================================================================

    @Transactional(readOnly = true)
    public PageResponse<OrderDto> listOrders(Long userId, String tab, String symbol, OrderSide side,
                                             Instant from, Instant to, int page, int size, String sort) {
        TradingAccount account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("ACCOUNT_NOT_FOUND",
                        "No trading account exists for this user."));
        List<OrderStatus> statuses = TABS.getOrDefault(
                tab == null ? "all" : tab.trim().toLowerCase(), TABS.get("all"));
        Long instrumentId = null;
        if (symbol != null && !symbol.isBlank()) {
            instrumentId = instrumentService.require(symbol).getId();
        }
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 200), resolveSort(sort));
        Page<Order> result = orderRepository.search(account.getId(), statuses, instrumentId, side, from, to, pageable);
        return PageResponse.of(result, this::toDto);
    }

    @Transactional(readOnly = true)
    public OrderDto getOrder(Long userId, Long orderId) {
        TradingAccount account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("ACCOUNT_NOT_FOUND",
                        "No trading account exists for this user."));
        Order order = orderRepository.findByIdAndAccountId(orderId, account.getId())
                .orElseThrow(() -> new ResourceNotFoundException("ORDER_NOT_FOUND", "Order " + orderId + " was not found."));
        return toDto(order);
    }

    @Transactional(readOnly = true)
    public List<OrderDto> openOrders(Long userId) {
        TradingAccount account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("ACCOUNT_NOT_FOUND",
                        "No trading account exists for this user."));
        return orderRepository.searchAll(account.getId(),
                        List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED),
                        null, null, null, null)
                .stream().map(this::toDto).toList();
    }

    // =======================================================================================
    // Mapping
    // =======================================================================================

    public OrderDto toDto(Order order) {
        TradingAccount account = accountRepository.findById(order.getAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("ACCOUNT_NOT_FOUND",
                        "No trading account exists for order " + order.getOrderRef() + "."));
        return toDto(order, account.getCurrency());
    }

    private OrderDto toDto(Order order, String accountCurrency) {
        Instrument instrument = instrumentService.find(order.getInstrumentId()).orElse(null);
        BigDecimal orderValue = order.getLimitPrice() != null
                ? currencyConverter.toLedger(instrument,
                        order.getQuantity().multiply(order.getLimitPrice(), Decimals.MC), accountCurrency)
                : (order.getAvgFillPrice() != null
                ? currencyConverter.toLedger(instrument,
                        order.getQuantity().multiply(order.getAvgFillPrice(), Decimals.MC), accountCurrency)
                : null);

        BigDecimal executedValue = order.getAvgFillPrice() == null ? null
                : currencyConverter.toLedger(instrument,
                        order.getFilledQuantity().multiply(order.getAvgFillPrice(), Decimals.MC), accountCurrency);

        BigDecimal chargedOn = executedValue != null && executedValue.signum() > 0 ? executedValue : orderValue;
        ChargesBreakdownDto breakdown = chargedOn == null || chargedOn.signum() <= 0
                ? ChargesBreakdownDto.zero()
                : chargesCalculator.calculate(chargedOn, order.getSide());

        return new OrderDto(
                order.getId(),
                order.getOrderRef(),
                instrument == null ? "?" : instrument.getSymbol(),
                instrument == null ? "Unknown instrument" : instrument.getName(),
                instrument == null ? null : instrument.getExchange(),
                instrument == null ? null : instrument.getCurrency(),
                accountCurrency,
                order.getSide(),
                order.getOrderType(),
                order.getStatus(),
                statusLabel(order.getStatus()),
                order.getQuantity(),
                order.getLimitPrice(),
                order.getStopPrice(),
                order.getFilledQuantity(),
                order.getAvgFillPrice(),
                orderValue,
                executedValue,
                order.getEstimatedCharges(),
                order.getCharges(),
                breakdown,
                order.getReservedCash(),
                order.getRealizedPnl(),
                order.getNetCashImpact(),
                order.getPositionStopLoss(),
                order.getPositionTarget(),
                order.getRejectReason(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getTriggeredAt(),
                order.getExecutedAt(),
                order.getCancelledAt(),
                order.getStatus().isOpen());
    }

    public static String statusLabel(OrderStatus status) {
        return switch (status) {
            case PENDING -> "Working";
            case PARTIALLY_FILLED -> "Partially filled";
            case EXECUTED -> "Executed";
            case CANCELLED -> "Cancelled";
            case REJECTED -> "Rejected";
            case EXPIRED -> "Expired";
        };
    }

    private Sort resolveSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        Sort.Direction direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        List<String> allowed = List.of("createdAt", "executedAt", "status", "quantity", "avgFillPrice");
        if (!allowed.contains(field)) {
            throw new BadRequestException("INVALID_SORT", "Unsupported sort field '" + field + "'.");
        }
        return Sort.by(direction, field);
    }

    private static String trim(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    /** Guard used by callers that must not proceed when the account is not the caller's. */
    public void assertOwnership(TradingAccount account, Order order) {
        if (!order.getAccountId().equals(account.getId())) {
            throw new ForbiddenException("This order belongs to a different account.");
        }
    }
}

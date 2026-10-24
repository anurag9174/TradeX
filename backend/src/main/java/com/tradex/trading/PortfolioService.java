package com.tradex.trading;

import com.tradex.dto.portfolio.HoldingDto;
import com.tradex.dto.portfolio.PerformanceDto;
import com.tradex.dto.portfolio.PortfolioDto;
import com.tradex.entity.Holding;
import com.tradex.entity.Instrument;
import com.tradex.entity.Trade;
import com.tradex.entity.TransactionLedger;
import com.tradex.entity.TradingAccount;
import com.tradex.enums.OrderSide;
import com.tradex.enums.OrderStatus;
import com.tradex.marketdata.MarketDataRegistry;
import com.tradex.marketdata.MarketDataService;
import com.tradex.marketdata.QuoteSnapshot;
import com.tradex.repository.HoldingRepository;
import com.tradex.repository.OrderRepository;
import com.tradex.repository.TransactionLedgerRepository;
import com.tradex.repository.TradeRepository;
import com.tradex.service.InstrumentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Marks positions to market and produces every derived number the UI shows.
 *
 * <h2>Valuation contract</h2>
 * <pre>
 *   holdingsValue      = Σ quantity * currentPrice          (converted to the ledger currency)
 *   investedValue      = Σ quantity * avgCost                (avgCost includes capitalised buy charges)
 *   portfolioValue     = cashBalance + reservedCash + holdingsValue
 *   unrealizedPnl      = Σ quantity * (currentPrice - avgCost)
 *   totalReturn        = portfolioValue - initialBalance
 *   totalReturnPercent = totalReturn / initialBalance * 100
 * </pre>
 *
 * <p>Amounts in an instrument's own quote currency (for example Delta Exchange USD perpetuals)
 * are converted to the ledger currency with {@link CurrencyConverter} before they enter any of
 * the sums above, so every number in this class is in one currency. Prices themselves
 * ({@code currentPrice}, {@code avgCost}) always stay in the instrument's quote currency.</p>
 *
 * Because buy charges are capitalised into {@code avgCost}, the identity
 * <pre>  portfolioValue == initialBalance + realizedPnl + unrealizedPnl  </pre>
 * holds exactly. {@code PortfolioAccountingTest} asserts it after arbitrary sequences of trades.
 */
@Service
public class PortfolioService {

    private final HoldingRepository holdingRepository;
    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final TransactionLedgerRepository ledgerRepository;
    private final InstrumentService instrumentService;
    private final MarketDataService marketDataService;
    private final MarketDataRegistry registry;
    private final TradingClock clock;
    private final CurrencyConverter currencyConverter;

    public PortfolioService(HoldingRepository holdingRepository,
                            TradeRepository tradeRepository,
                            OrderRepository orderRepository,
                            TransactionLedgerRepository ledgerRepository,
                            InstrumentService instrumentService,
                            MarketDataService marketDataService,
                            MarketDataRegistry registry,
                            TradingClock clock,
                            CurrencyConverter currencyConverter) {
        this.holdingRepository = holdingRepository;
        this.tradeRepository = tradeRepository;
        this.orderRepository = orderRepository;
        this.ledgerRepository = ledgerRepository;
        this.instrumentService = instrumentService;
        this.marketDataService = marketDataService;
        this.registry = registry;
        this.clock = clock;
        this.currencyConverter = currencyConverter;
    }

    /** One priced position. */
    public record PositionView(
            Holding holding,
            Instrument instrument,
            String accountCurrency,
            BigDecimal quantity,
            BigDecimal avgCost,
            BigDecimal currentPrice,
            boolean priced,
            BigDecimal previousClose,
            BigDecimal investedValue,
            BigDecimal marketValue,
            BigDecimal unrealizedPnl,
            BigDecimal dayBasePrice,
            BigDecimal dayPnl) {

        public BigDecimal unrealizedPnlPercent() {
            return Decimals.percentChange(investedValue, marketValue);
        }
    }

    /** Aggregated valuation of one account. */
    public record Valuation(
            TradingAccount account,
            List<PositionView> positions,
            BigDecimal availableCash,
            BigDecimal reservedCash,
            BigDecimal totalCash,
            BigDecimal investedValue,
            BigDecimal holdingsValue,
            BigDecimal portfolioValue,
            BigDecimal unrealizedPnl,
            BigDecimal unrealizedPnlPercent,
            BigDecimal realizedPnl,
            BigDecimal totalReturn,
            BigDecimal totalReturnPercent,
            BigDecimal todaysPnl,
            BigDecimal todaysRealizedPnl,
            BigDecimal todaysCharges,
            BigDecimal todaysInvestedValue,
            BigDecimal todaysBaseValue,
            int openOrderCount,
            int tradeCountToday) {
    }

    // =======================================================================================
    // Valuation
    // =======================================================================================

    @Transactional(readOnly = true)
    public Valuation value(TradingAccount account) {
        List<Holding> holdings = holdingRepository.findByAccountIdOrderByIdAsc(account.getId());
        List<PositionView> positions = new ArrayList<>(holdings.size());

        BigDecimal investedValue = BigDecimal.ZERO;
        BigDecimal marketValue = BigDecimal.ZERO;
        BigDecimal todaysBaseValue = BigDecimal.ZERO;
        LocalDate today = clock.today();

        for (Holding holding : holdings) {
            Instrument instrument = instrumentService.require(holding.getInstrumentId());
            QuoteSnapshot quote = marketDataService.quoteSnapshot(instrument);
            boolean priced = quote.lastPrice() != null;
            BigDecimal price = priced ? quote.lastPrice() : holding.getAvgCost();

            BigDecimal invested = currencyConverter.toLedger(instrument,
                    holding.getQuantity().multiply(holding.getAvgCost(), Decimals.MC), account.getCurrency());
            BigDecimal market = currencyConverter.toLedger(instrument,
                    holding.getQuantity().multiply(price, Decimals.MC), account.getCurrency());

            // Start-of-day reference for the intraday attribution.
            boolean openedToday = holding.getOpenedAt() != null
                    && clock.dateOf(holding.getOpenedAt()).equals(today);
            BigDecimal dayBase = openedToday || quote.previousClose() == null
                    ? holding.getAvgCost()
                    : quote.previousClose();
            BigDecimal dayPnl = currencyConverter.toLedger(instrument,
                    price.subtract(dayBase).multiply(holding.getQuantity(), Decimals.MC), account.getCurrency());

            investedValue = investedValue.add(invested);
            marketValue = marketValue.add(market);
            todaysBaseValue = todaysBaseValue.add(currencyConverter.toLedger(instrument,
                    holding.getQuantity().multiply(dayBase, Decimals.MC), account.getCurrency()));

            positions.add(new PositionView(holding, instrument, account.getCurrency(),
                    holding.getQuantity(), holding.getAvgCost(),
                    price, priced, quote.previousClose(), invested, market, market.subtract(invested),
                    dayBase, dayPnl));
        }

        investedValue = Decimals.money(investedValue);
        marketValue = Decimals.money(marketValue);

        BigDecimal availableCash = Decimals.money(account.getCashBalance());
        BigDecimal reservedCash = Decimals.money(account.getReservedCash());
        BigDecimal totalCash = Decimals.money(availableCash.add(reservedCash));
        BigDecimal portfolioValue = Decimals.money(totalCash.add(marketValue));
        BigDecimal unrealized = Decimals.money(marketValue.subtract(investedValue));
        BigDecimal realized = Decimals.money(Decimals.nullToZero(account.getRealizedPnl()));
        BigDecimal totalReturn = Decimals.money(portfolioValue.subtract(account.getInitialBalance()));

        BigDecimal realizedToday = Decimals.money(Decimals.nullToZero(
                tradeRepository.sumRealizedPnlSince(account.getId(), clock.startOfToday())));
        BigDecimal chargesToday = Decimals.money(Decimals.nullToZero(
                tradeRepository.sumChargesSince(account.getId(), clock.startOfToday())));
        BigDecimal openToday = Decimals.money(marketValue.subtract(todaysBaseValue));

        // Realised legs booked today already reflect today's mark of closed positions.
        BigDecimal todaysPnl = Decimals.money(realizedToday.add(openToday));

        int openOrders = (int) orderRepository.countByAccountAndStatusIn(account.getId(),
                Set.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED));
        int tradesToday = (int) tradeRepository.countSince(account.getId(), clock.startOfToday());

        return new Valuation(
                account,
                List.copyOf(positions),
                availableCash,
                reservedCash,
                totalCash,
                investedValue,
                marketValue,
                portfolioValue,
                unrealized,
                Decimals.percentChange(investedValue, marketValue),
                realized,
                totalReturn,
                Decimals.percentChange(account.getInitialBalance(), portfolioValue),
                todaysPnl,
                realizedToday,
                chargesToday,
                marketValue,
                Decimals.money(todaysBaseValue),
                openOrders,
                tradesToday);
    }

    /** Convenience for the guards: the day's net result for one account. */
    @Transactional(readOnly = true)
    public BigDecimal todaysNetPnl(TradingAccount account) {
        return value(account).todaysPnl();
    }

    // =======================================================================================
    // DTOs
    // =======================================================================================

    public HoldingDto toDto(PositionView view, BigDecimal totalMarketValue) {
        Holding holding = view.holding();
        BigDecimal change = view.previousClose() == null
                ? BigDecimal.ZERO
                : view.currentPrice().subtract(view.previousClose());
        BigDecimal changePercent = view.previousClose() == null
                ? BigDecimal.ZERO
                : Decimals.percentChange(view.previousClose(), view.currentPrice());
        BigDecimal allocation = totalMarketValue.signum() == 0
                ? BigDecimal.ZERO
                : view.marketValue().multiply(Decimals.HUNDRED, Decimals.MC).divide(totalMarketValue, Decimals.MC);

        return new HoldingDto(
                holding.getId(),
                view.instrument().getSymbol(),
                view.instrument().getName(),
                view.instrument().getExchange(),
                view.instrument().getAssetClass().name(),
                view.instrument().getCurrency(),
                view.quantity(),
                view.avgCost(),
                view.currentPrice(),
                view.priced(),
                view.investedValue(),
                view.marketValue(),
                view.unrealizedPnl(),
                view.unrealizedPnlPercent(),
                Decimals.money(Decimals.nullToZero(holding.getRealizedPnl())),
                currencyConverter.toLedger(view.instrument(), change.multiply(view.quantity(), Decimals.MC),
                        view.accountCurrency()),
                changePercent.setScale(4, RoundingMode.HALF_UP),
                view.dayPnl(),
                scale(allocation),
                holding.getStopLossPrice(),
                holding.getTargetPrice(),
                riskReward(view),
                holding.getOpenedAt(),
                holding.getLastTradedAt());
    }

    private static BigDecimal riskReward(PositionView view) {
        Holding holding = view.holding();
        if (holding.getStopLossPrice() == null || holding.getTargetPrice() == null) {
            return null;
        }
        BigDecimal risk = view.avgCost().subtract(holding.getStopLossPrice());
        BigDecimal reward = holding.getTargetPrice().subtract(view.avgCost());
        if (risk.signum() <= 0) {
            return null;
        }
        return reward.divide(risk, 4, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public List<HoldingDto> holdings(TradingAccount account) {
        Valuation valuation = value(account);
        return valuation.positions().stream()
                .map(view -> toDto(view, valuation.holdingsValue()))
                .toList();
    }

    /**
     * A note describing the nominal conversions applicable to this valuation's positions, or
     * {@code null} when every amount is already in the ledger currency.
     */
    public String fxNote(Valuation valuation) {
        return currencyConverter.ledgerNote(valuation.positions().stream()
                .map(PositionView::instrument).toList(), valuation.account().getCurrency());
    }

    @Transactional(readOnly = true)
    public PortfolioDto portfolio(TradingAccount account) {
        Valuation valuation = value(account);
        List<HoldingDto> holdingDtos = valuation.positions().stream()
                .map(view -> toDto(view, valuation.holdingsValue()))
                .toList();
        List<PortfolioDto.AllocationSlice> allocation = valuation.positions().stream()
                .map(view -> new PortfolioDto.AllocationSlice(
                        view.instrument().getSymbol(),
                        view.instrument().getName(),
                        view.marketValue(),
                        valuation.holdingsValue().signum() == 0
                                ? BigDecimal.ZERO
                                : scale(view.marketValue().multiply(Decimals.HUNDRED, Decimals.MC)
                                .divide(valuation.holdingsValue(), Decimals.MC))))
                .sorted((a, b) -> b.marketValue().compareTo(a.marketValue()))
                .toList();

        return new PortfolioDto(
                account.getAccountNumber(),
                account.getCurrency(),
                Decimals.money(account.getInitialBalance()),
                valuation.availableCash(),
                valuation.reservedCash(),
                valuation.totalCash(),
                valuation.investedValue(),
                valuation.holdingsValue(),
                valuation.portfolioValue(),
                valuation.unrealizedPnl(),
                scale(valuation.unrealizedPnlPercent()),
                valuation.realizedPnl(),
                valuation.totalReturn(),
                scale(valuation.totalReturnPercent()),
                valuation.todaysPnl(),
                fxNote(valuation),
                valuation.positions().size(),
                valuation.openOrderCount(),
                holdingDtos,
                allocation,
                registry.activeSource(),
                registry.activeLabel(),
                Instant.now());
    }

    // =======================================================================================
    // Performance
    // =======================================================================================

    /**
     * Equity curve and trade statistics between two trading dates.
     *
     * <p>The curve is reconstructed from the immutable ledger (cash) and realised P&amp;L, walking one
     * day at a time. Because positions are marked at their average cost for historical points, the
     * series is an honest <em>realised</em> equity curve rather than a fabricated mark-to-market
     * reconstruction - there is no historical tick data to mark against.</p>
     */
    @Transactional(readOnly = true)
    public PerformanceDto performance(TradingAccount account, LocalDate from, LocalDate to) {
        LocalDate start = from == null ? clock.today().minusDays(29) : from;
        LocalDate end = to == null ? clock.today() : to;
        if (end.isBefore(start)) {
            LocalDate swap = start;
            start = end;
            end = swap;
        }
        // The account cannot have a meaningful equity before it existed; clamping avoids a fake
        // zero-to-grant jump that would misreport the curve and the percent change.
        if (account.getCreatedAt() != null) {
            LocalDate createdOn = clock.dateOf(account.getCreatedAt());
            if (start.isBefore(createdOn)) {
                start = createdOn;
            }
            if (end.isBefore(start)) {
                end = start;
            }
        }
        if (account.getLastResetAt() != null) {
            LocalDate resetOn = clock.dateOf(account.getLastResetAt());
            if (start.isBefore(resetOn)) {
                start = resetOn;
            }
            if (end.isBefore(start)) {
                end = start;
            }
        }
        Instant fromInstant = clock.startOfDay(start);
        Instant toInstant = clock.startOfDay(end, 1);

        List<Trade> trades = tradeRepository.findByAccountIdOrderByExecutedAtDesc(account.getId()).stream()
                .filter(trade -> !trade.getExecutedAt().isBefore(fromInstant) && trade.getExecutedAt().isBefore(toInstant))
                .toList();

        Map<LocalDate, BigDecimal> realizedByDay = new LinkedHashMap<>();
        Map<LocalDate, BigDecimal> chargesByDay = new LinkedHashMap<>();
        Map<LocalDate, Integer> countByDay = new LinkedHashMap<>();
        Map<Long, BigDecimal> pnlByInstrument = new LinkedHashMap<>();
        Map<Long, Integer> countByInstrument = new LinkedHashMap<>();
        Map<Long, BigDecimal> chargesByInstrument = new LinkedHashMap<>();

        BigDecimal realizedTotal = BigDecimal.ZERO;
        BigDecimal chargesTotal = BigDecimal.ZERO;
        BigDecimal best = null;
        BigDecimal worst = null;
        int wins = 0;
        int losses = 0;

        for (Trade trade : trades) {
            LocalDate day = clock.dateOf(trade.getExecutedAt());
            BigDecimal realized = Decimals.nullToZero(trade.getRealizedPnl());
            BigDecimal charges = Decimals.nullToZero(trade.getCharges());

            realizedByDay.merge(day, realized, BigDecimal::add);
            chargesByDay.merge(day, charges, BigDecimal::add);
            countByDay.merge(day, 1, Integer::sum);
            chargesByInstrument.merge(trade.getInstrumentId(), charges, BigDecimal::add);

            realizedTotal = realizedTotal.add(realized);
            chargesTotal = chargesTotal.add(charges);

            if (realized.signum() != 0 && trade.getSide() == com.tradex.enums.OrderSide.SELL) {
                pnlByInstrument.merge(trade.getInstrumentId(), realized, BigDecimal::add);
                countByInstrument.merge(trade.getInstrumentId(), 1, Integer::sum);
                if (realized.signum() > 0) {
                    wins++;
                    best = best == null ? realized : Decimals.max(best, realized);
                } else {
                    losses++;
                    worst = worst == null ? realized : Decimals.min(worst, realized);
                }
            }
        }

        BigDecimal averageWin = wins == 0 ? BigDecimal.ZERO
                : Decimals.divide(realizedByDay.values().stream().filter(v -> v.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add), BigDecimal.valueOf(wins));
        BigDecimal grossWin = realizedByDay.values().stream().filter(v -> v.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossLoss = realizedByDay.values().stream().filter(v -> v.signum() < 0).map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal averageLoss = losses == 0 ? BigDecimal.ZERO
                : Decimals.divide(grossLoss, BigDecimal.valueOf(losses));
        BigDecimal profitFactor = grossLoss.signum() == 0
                ? (grossWin.signum() > 0 ? new BigDecimal("999") : BigDecimal.ZERO)
                : grossWin.divide(grossLoss, 4, RoundingMode.HALF_UP);
        BigDecimal winRate = (wins + losses) == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(wins).multiply(Decimals.HUNDRED, Decimals.MC)
                .divide(BigDecimal.valueOf(wins + losses), Decimals.MC);

        // Equity curve: cash + realised P&L is exact; open positions are carried at average cost.
        List<PerformanceDto.EquityPoint> curve = new ArrayList<>();
        List<PerformanceDto.DailyPnlPoint> daily = new ArrayList<>();
        BigDecimal cumulativeRealized = BigDecimal.ZERO;
        BigDecimal peak = null;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        BigDecimal maxDrawdownPercent = BigDecimal.ZERO;

        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            BigDecimal realized = Decimals.money(realizedByDay.getOrDefault(day, BigDecimal.ZERO));
            BigDecimal charges = Decimals.money(chargesByDay.getOrDefault(day, BigDecimal.ZERO));
            int count = countByDay.getOrDefault(day, 0);
            cumulativeRealized = cumulativeRealized.add(realized);

            daily.add(new PerformanceDto.DailyPnlPoint(day, realized, charges, Decimals.money(realized), count));
            // Cash is reconstructed from the ledger below; the curve point is filled afterwards.
            curve.add(new PerformanceDto.EquityPoint(day, null, null, null, Decimals.money(cumulativeRealized)));
        }

        List<TransactionLedger> entries = ledgerRepository.findByAccountIdOrderByCreatedAtAsc(account.getId()).stream()
                .filter(entry -> account.getCurrency().equalsIgnoreCase(entry.getCurrency()))
                .filter(entry -> account.getLastResetAt() == null
                        || !entry.getCreatedAt().isBefore(account.getLastResetAt()))
                .toList();
        Map<LocalDate, BigDecimal> cashByDay = new LinkedHashMap<>();
        BigDecimal runningCash = BigDecimal.ZERO;
        int cursor = 0;
        if (account.getLastResetAt() != null) {
            for (int index = 0; index < entries.size(); index++) {
                TransactionLedger entry = entries.get(index);
                if (entry.getEntryType() == com.tradex.enums.LedgerEntryType.ACCOUNT_RESET) {
                    runningCash = Decimals.money(entry.getBalanceAfter().add(entry.getReservedAfter()));
                    cursor = index + 1;
                }
            }
        }
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            Instant dayEnd = clock.startOfDay(day, 1);
            while (cursor < entries.size() && entries.get(cursor).getCreatedAt().isBefore(dayEnd)) {
                runningCash = runningCash.add(Decimals.nullToZero(entries.get(cursor).getAmount()))
                        .add(Decimals.nullToZero(entries.get(cursor).getReservedDelta()));
                cursor++;
            }
            cashByDay.put(day, Decimals.money(runningCash));
        }

        List<Trade> chronological = new ArrayList<>(tradeRepository.findByAccountIdOrderByExecutedAtDesc(account.getId()));
        chronological.sort((a, b) -> a.getExecutedAt().compareTo(b.getExecutedAt()));

        List<PerformanceDto.EquityPoint> filled = new ArrayList<>(curve.size());
        for (PerformanceDto.EquityPoint point : curve) {
            BigDecimal cash = cashByDay.getOrDefault(point.date(), BigDecimal.ZERO);
            BigDecimal invested = investedValueOn(chronological, point.date());
            BigDecimal equity = Decimals.money(cash.add(invested));
            if (peak == null || equity.compareTo(peak) > 0) {
                peak = equity;
            }
            BigDecimal drawdown = Decimals.money(peak.subtract(equity));
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
                maxDrawdownPercent = peak.signum() == 0 ? BigDecimal.ZERO
                        : drawdown.multiply(Decimals.HUNDRED, Decimals.MC).divide(peak, Decimals.MC);
            }
            filled.add(new PerformanceDto.EquityPoint(point.date(), equity, cash, invested, point.realizedPnl()));
        }

        BigDecimal startingValue;
        if (account.getCreatedAt() != null && start.equals(clock.dateOf(account.getCreatedAt()))) {
            // The window begins on the day the account was funded, so the base is the one-time
            // virtual grant itself. Using end-of-day equity here would hide the first day's P&L.
            startingValue = Decimals.money(account.getInitialBalance());
        } else {
            startingValue = filled.isEmpty() ? Decimals.money(account.getInitialBalance())
                    : filled.get(0).portfolioValue();
        }
        BigDecimal endingValue = filled.isEmpty() ? account.getInitialBalance()
                : filled.get(filled.size() - 1).portfolioValue();
        BigDecimal netChange = Decimals.money(endingValue.subtract(startingValue));

        Map<Long, Instrument> instruments = new LinkedHashMap<>();
        for (Long id : pnlByInstrument.keySet()) {
            instruments.put(id, instrumentService.require(id));
        }
        List<PerformanceDto.InstrumentPnl> byInstrument = pnlByInstrument.entrySet().stream()
                .map(entry -> new PerformanceDto.InstrumentPnl(
                        instruments.get(entry.getKey()).getSymbol(),
                        instruments.get(entry.getKey()).getName(),
                        Decimals.money(entry.getValue()),
                        countByInstrument.getOrDefault(entry.getKey(), 0),
                        Decimals.money(chargesByInstrument.getOrDefault(entry.getKey(), BigDecimal.ZERO))))
                .sorted((a, b) -> b.realizedPnl().compareTo(a.realizedPnl()))
                .toList();

        return new PerformanceDto(
                account.getAccountNumber(),
                account.getCurrency(),
                start,
                end,
                startingValue,
                endingValue,
                netChange,
                Decimals.percentChange(startingValue, endingValue),
                Decimals.money(realizedTotal),
                Decimals.money(value(account).unrealizedPnl()),
                Decimals.money(chargesTotal),
                investedValueOn(chronological, end.plusDays(1)),
                trades.size(),
                wins,
                losses,
                scale(winRate),
                Decimals.money(averageWin),
                Decimals.money(averageLoss),
                profitFactor,
                Decimals.money(best == null ? BigDecimal.ZERO : best),
                Decimals.money(worst == null ? BigDecimal.ZERO : worst),
                maxDrawdown,
                scale(maxDrawdownPercent),
                filled,
                daily,
                byInstrument);
    }

    /**
     * Position book value carried into {@code day}. Reconstructed from trades: buys add capitalised
     * cost, sells release it at average cost. Exact, because average cost is what the platform uses.
     */
    BigDecimal investedValueOn(List<Trade> chronologicalTrades, LocalDate day) {
        Instant dayEnd = clock.startOfDay(day, 1);
        BigDecimal invested = BigDecimal.ZERO;
        Map<Long, BigDecimal> quantityByInstrument = new LinkedHashMap<>();
        Map<Long, BigDecimal> costByInstrument = new LinkedHashMap<>();
        for (Trade trade : chronologicalTrades) {
            if (!trade.getExecutedAt().isBefore(dayEnd)) {
                break;
            }
            BigDecimal quantity = Decimals.nullToZero(quantityByInstrument.get(trade.getInstrumentId()));
            BigDecimal cost = Decimals.nullToZero(costByInstrument.get(trade.getInstrumentId()));
            if (trade.getSide() == OrderSide.BUY) {
                quantity = quantity.add(trade.getQuantity());
                cost = cost.add(Decimals.nullToZero(trade.getGrossAmount()))
                        .add(Decimals.nullToZero(trade.getCharges()));
            } else {
                BigDecimal avg = quantity.signum() == 0 ? BigDecimal.ZERO
                        : cost.divide(quantity, Decimals.QTY_SCALE, RoundingMode.HALF_UP);
                BigDecimal released = avg.multiply(trade.getQuantity(), Decimals.MC);
                quantity = quantity.subtract(trade.getQuantity());
                cost = cost.subtract(released);
                if (quantity.signum() <= 0) {
                    quantity = BigDecimal.ZERO;
                    cost = BigDecimal.ZERO;
                }
            }
            quantityByInstrument.put(trade.getInstrumentId(), quantity);
            costByInstrument.put(trade.getInstrumentId(), cost);
        }
        for (BigDecimal value : costByInstrument.values()) {
            invested = invested.add(value);
        }
        return Decimals.money(invested);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }
}

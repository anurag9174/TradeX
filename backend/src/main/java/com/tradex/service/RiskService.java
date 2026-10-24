package com.tradex.service;

import com.tradex.dto.risk.CreateJournalEntryRequest;
import com.tradex.dto.risk.JournalEntryDto;
import com.tradex.dto.risk.OpenPositionRisk;
import com.tradex.dto.risk.PositionSizeRequest;
import com.tradex.dto.risk.PositionSizeResponse;
import com.tradex.dto.risk.RiskSummaryDto;
import com.tradex.entity.Holding;
import com.tradex.entity.Instrument;
import com.tradex.entity.JournalEntry;
import com.tradex.entity.TradingAccount;
import com.tradex.exception.BadRequestException;
import com.tradex.exception.ResourceNotFoundException;
import com.tradex.repository.JournalEntryRepository;
import com.tradex.repository.TradingAccountRepository;
import com.tradex.trading.CurrencyConverter;
import com.tradex.trading.Decimals;
import com.tradex.trading.PortfolioService;
import com.tradex.trading.RiskGuardService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Risk-management console.
 *
 * <p>Everything here is an educational calculation on the user's own simulated book: exposure,
 * capital at risk implied by attached stops, position sizing and a trading journal. Nothing is
 * investment advice and the API says so in a {@code disclaimer} field on every response.</p>
 */
@Service
public class RiskService {

    public static final String DISCLAIMER =
            "TradeX is a paper-trading simulator for practice and education. "
                    + "These figures describe your virtual account only and are not investment advice.";

    private final TradingAccountRepository accountRepository;
    private final JournalEntryRepository journalRepository;
    private final PortfolioService portfolioService;
    private final RiskGuardService riskGuardService;
    private final InstrumentService instrumentService;
    private final CurrencyConverter currencyConverter;

    public RiskService(TradingAccountRepository accountRepository,
                       JournalEntryRepository journalRepository,
                       PortfolioService portfolioService,
                       RiskGuardService riskGuardService,
                       InstrumentService instrumentService,
                       CurrencyConverter currencyConverter) {
        this.accountRepository = accountRepository;
        this.journalRepository = journalRepository;
        this.portfolioService = portfolioService;
        this.riskGuardService = riskGuardService;
        this.instrumentService = instrumentService;
        this.currencyConverter = currencyConverter;
    }

    // =======================================================================================
    // Summary
    // =======================================================================================

    @Transactional(readOnly = true)
    public RiskSummaryDto summary(Long userId) {
        TradingAccount account = requireAccount(userId);
        PortfolioService.Valuation valuation = portfolioService.value(account);

        BigDecimal portfolioValue = valuation.portfolioValue();
        BigDecimal exposurePercent = percentOf(valuation.holdingsValue(), portfolioValue);

        BigDecimal limit = Decimals.money(Decimals.nullToZero(account.getDailyLossLimit()));
        boolean breached = riskGuardService.isLimitBreached(account, valuation.todaysPnl());
        BigDecimal lossUsed = DecimalSafe.max(BigDecimal.ZERO, valuation.todaysPnl().negate());
        BigDecimal lossRemaining = DecimalSafe.max(BigDecimal.ZERO, limit.subtract(lossUsed));

        List<OpenPositionRisk> positions = new ArrayList<>(valuation.positions().size());
        BigDecimal totalOpenRisk = BigDecimal.ZERO;
        BigDecimal largestPercent = BigDecimal.ZERO;
        String largestSymbol = null;
        Map<String, BigDecimal> byAssetClass = new LinkedHashMap<>();

        for (PortfolioService.PositionView view : valuation.positions()) {
            BigDecimal allocation = percentOf(view.marketValue(), portfolioValue);
            BigDecimal exposure = percentOf(view.marketValue(), portfolioValue);

            BigDecimal riskAtStop = null;
            BigDecimal rewardAtTarget = null;
            BigDecimal rr = null;
            BigDecimal capitalAtRiskPercent = null;
            String note;

            Holding holding = view.holding();
            if (holding.getStopLossPrice() != null) {
                riskAtStop = currencyConverter.toLedger(view.instrument(),
                        view.avgCost().subtract(holding.getStopLossPrice())
                                .multiply(view.quantity(), Decimals.MC), view.accountCurrency());
                if (riskAtStop.signum() > 0) {
                    totalOpenRisk = totalOpenRisk.add(riskAtStop);
                    capitalAtRiskPercent = percentOf(riskAtStop, portfolioValue);
                }
            }
            if (holding.getTargetPrice() != null) {
                rewardAtTarget = currencyConverter.toLedger(view.instrument(),
                        holding.getTargetPrice().subtract(view.avgCost())
                                .multiply(view.quantity(), Decimals.MC), view.accountCurrency());
            }
            if (riskAtStop != null && rewardAtTarget != null && riskAtStop.signum() > 0) {
                rr = rewardAtTarget.divide(riskAtStop, 4, RoundingMode.HALF_UP);
            }

            note = buildPositionNote(view, holding, riskAtStop, allocation, rr);
            if (allocation.compareTo(largestPercent) > 0) {
                largestPercent = allocation;
                largestSymbol = view.instrument().getSymbol();
            }

            byAssetClass.merge(view.instrument().getAssetClass().name(), view.marketValue(), BigDecimal::add);

            positions.add(new OpenPositionRisk(
                    view.instrument().getSymbol(),
                    view.instrument().getName(),
                    view.instrument().getAssetClass().name(),
                    view.instrument().getCurrency(),
                    view.quantity(),
                    view.avgCost(),
                    view.currentPrice(),
                    view.priced(),
                    view.investedValue(),
                    view.marketValue(),
                    view.unrealizedPnl(),
                    scale(view.unrealizedPnlPercent()),
                    scale(allocation),
                    scale(exposure),
                    holding.getStopLossPrice(),
                    holding.getTargetPrice(),
                    riskAtStop,
                    rewardAtTarget,
                    rr,
                    scale(capitalAtRiskPercent),
                    note));
        }
        positions.sort(Comparator.comparing(OpenPositionRisk::marketValue).reversed());

        Map<String, BigDecimal> exposureByClass = new LinkedHashMap<>();
        byAssetClass.forEach((key, value) -> exposureByClass.put(key, percentOf(value, portfolioValue)));

        List<String> warnings = new ArrayList<>();
        if (exposurePercent.compareTo(new BigDecimal("80")) > 0) {
            warnings.add("Over 80% of the portfolio is deployed. There is little cash left to average into "
                    + "a position or to absorb a drawdown.");
        }
        long unprotected = valuation.positions().stream().filter(p -> p.holding().getStopLossPrice() == null).count();
        if (unprotected > 0) {
            warnings.add(unprotected + " position(s) have no stop-loss attached in the risk plan.");
        }
        if (largestPercent.compareTo(new BigDecimal("35")) > 0) {
            warnings.add("Largest position " + largestSymbol + " is " + scale(largestPercent).toPlainString()
                    + "% of the portfolio. Consider spreading the risk.");
        }
        if (account.isDailyLossLimitEnabled() && limit.signum() > 0 && lossRemaining.signum() > 0
                && lossRemaining.compareTo(limit.multiply(new BigDecimal("0.25"), Decimals.MC)) <= 0) {
            warnings.add("More than 75% of the daily loss budget has been used.");
        }

        return new RiskSummaryDto(
                account.getCurrency(),
                portfolioValue,
                valuation.availableCash(),
                valuation.holdingsValue(),
                scale(exposurePercent),
                valuation.todaysRealizedPnl(),
                Decimals.money(valuation.holdingsValue().subtract(valuation.todaysBaseValue())),
                valuation.todaysPnl(),
                valuation.todaysCharges(),
                account.isDailyLossLimitEnabled(),
                limit,
                lossUsed,
                lossRemaining,
                breached,
                account.isTradingBlocked() || breached,
                (account.isTradingBlocked() || breached)
                        ? riskGuardService.blockedReason(account, valuation.todaysPnl()) : null,
                valuation.positions().size(),
                totalOpenRisk,
                scale(percentOf(totalOpenRisk, portfolioValue)),
                null,
                valuation.unrealizedPnl(),
                valuation.realizedPnl(),
                exposureByClass,
                positions,
                portfolioService.fxNote(valuation),
                warnings,
                DISCLAIMER);
    }

    private String buildPositionNote(PortfolioService.PositionView view, Holding holding,
                                     BigDecimal riskAtStop, BigDecimal allocation, BigDecimal rr) {
        if (riskAtStop == null) {
            return "No stop-loss attached. Downside is limited only by the position size.";
        }
        if (riskAtStop.signum() <= 0) {
            return "The stop sits above the average cost, so the plan is already free of loss risk.";
        }
        StringBuilder note = new StringBuilder();
        note.append("Stop at ").append(holding.getStopLossPrice().stripTrailingZeros().toPlainString())
                .append(" risks ").append(riskAtStop.toPlainString()).append(' ')
                .append(view.accountCurrency()).append(" (")
                .append(scale(percentOf(riskAtStop, view.marketValue())).toPlainString())
                .append("% of the position).");
        if (rr != null) {
            note.append(" Risk/reward ").append(rr.toPlainString()).append(":1.");
        }
        if (allocation.compareTo(new BigDecimal("30")) > 0) {
            note.append(" This is a concentrated position.");
        }
        return note.toString();
    }

    // =======================================================================================
    // Position sizing
    // =======================================================================================

    @Transactional(readOnly = true)
    public PositionSizeResponse positionSize(Long userId, PositionSizeRequest request) {
        TradingAccount account = requireAccount(userId);
        BigDecimal portfolioValue = portfolioService.value(account).portfolioValue();

        // Optional instrument context: prices are read in the instrument's own quote currency and
        // every money amount is converted to the ledger currency at the nominal rate.
        Instrument instrument = request.symbol() == null || request.symbol().isBlank()
                ? null
                : instrumentService.require(request.symbol());
        if (instrument != null) {
            currencyConverter.requireSameCurrencyForTrading(instrument, account.getCurrency());
        }
        BigDecimal fx = currencyConverter.factor(instrument, account.getCurrency());
        String priceCurrency = instrument == null ? account.getCurrency()
                : instrument.getCurrency();

        BigDecimal entry = request.entryPrice();
        BigDecimal stop = request.stopPrice();
        BigDecimal riskPerUnit = entry.subtract(stop);
        if (riskPerUnit.signum() <= 0) {
            throw new BadRequestException("INVALID_STOP",
                    "For a long position the stop must sit below the entry price. "
                            + "Entry " + entry.toPlainString() + ", stop " + stop.toPlainString() + ".");
        }
        // The risk budget is ledger money, so the stop distance must be too.
        BigDecimal riskPerUnitLedger = riskPerUnit.multiply(fx, Decimals.MC);

        BigDecimal riskPercent = request.riskPercent();
        BigDecimal capitalAtRisk = Decimals.money(
                portfolioValue.multiply(riskPercent, Decimals.MC).divide(Decimals.HUNDRED, Decimals.MC));

        BigDecimal rawQuantity = capitalAtRisk.divide(riskPerUnitLedger, Decimals.QTY_SCALE, RoundingMode.DOWN);

        BigDecimal lot = request.lotSize() == null ? BigDecimal.ONE : request.lotSize();
        if (lot.signum() <= 0) {
            lot = BigDecimal.ONE;
        }
        BigDecimal lots = rawQuantity.divide(lot, 0, RoundingMode.DOWN);
        BigDecimal quantity = decimalsQty(lots.multiply(lot));
        if (quantity.signum() <= 0) {
            throw new BadRequestException("POSITION_TOO_SMALL",
                    "The risk budget of " + capitalAtRisk.toPlainString() + " is smaller than one lot at this "
                            + "stop distance. Widen the budget or tighten the stop.");
        }

        BigDecimal notional = currencyConverter.toLedger(instrument, quantity.multiply(entry, Decimals.MC),
                account.getCurrency());
        BigDecimal allocationPercent = percentOf(notional, portfolioValue);
        BigDecimal residualRisk = Decimals.money(capitalAtRisk.subtract(riskPerUnitLedger.multiply(quantity, Decimals.MC)));

        BigDecimal target = request.targetPrice();
        BigDecimal rewardPerUnit = target == null ? null : target.subtract(entry);
        BigDecimal rr = (rewardPerUnit == null || rewardPerUnit.signum() <= 0) ? null
                : rewardPerUnit.divide(riskPerUnit, 4, RoundingMode.HALF_UP);
        BigDecimal assumedCharge = notional.multiply(new BigDecimal("0.0006"), Decimals.MC);
        BigDecimal potentialProfit = rewardPerUnit == null ? null
                : Decimals.money(rewardPerUnit.multiply(quantity, Decimals.MC).multiply(fx, Decimals.MC)
                .subtract(assumedCharge));
        BigDecimal potentialLoss = Decimals.money(riskPerUnitLedger.multiply(quantity, Decimals.MC)
                .add(assumedCharge));
        BigDecimal breakEven = entry.add(assumedCharge
                .divide(quantity.multiply(fx, Decimals.MC), Decimals.MONEY_SCALE, RoundingMode.HALF_UP));

        BigDecimal maxByAllocation = null;
        if (request.maxAllocationPercent() != null && request.maxAllocationPercent().signum() > 0) {
            BigDecimal cap = portfolioValue.multiply(request.maxAllocationPercent(), Decimals.MC)
                    .divide(Decimals.HUNDRED, Decimals.MC);
            maxByAllocation = decimalsQty(cap.divide(entry.multiply(fx, Decimals.MC), Decimals.QTY_SCALE, RoundingMode.DOWN)
                    .divide(lot, 0, RoundingMode.DOWN).multiply(lot));
        }
        BigDecimal maxByCapital = decimalsQty(account.getCashBalance()
                .divide(entry.multiply(fx, Decimals.MC), Decimals.QTY_SCALE, RoundingMode.DOWN)
                .divide(lot, 0, RoundingMode.DOWN).multiply(lot));

        StringBuilder rationale = new StringBuilder();
        rationale.append("At a ").append(scale(riskPercent).toPlainString()).append("% risk budget on a ")
                .append(portfolioValue.toPlainString()).append(" portfolio you can lose ")
                .append(capitalAtRisk.toPlainString()).append(". With a stop ")
                .append(riskPerUnit.stripTrailingZeros().toPlainString())
                .append(" below entry that supports ").append(quantity.stripTrailingZeros().toPlainString())
                .append(" units (").append(notional.toPlainString()).append(", ")
                .append(scale(allocationPercent).toPlainString()).append("% of the portfolio).");
        if (maxByAllocation != null && quantity.compareTo(maxByAllocation) > 0) {
            rationale.append(" That exceeds your ").append(request.maxAllocationPercent().stripTrailingZeros())
                    .append("% allocation cap of ").append(maxByAllocation.stripTrailingZeros().toPlainString())
                    .append(" units - consider the cap instead.");
        }
        if (fx.compareTo(BigDecimal.ONE) != 0) {
            rationale.append(" Entry and stop are ").append(priceCurrency)
                    .append(" prices; money amounts are converted at the nominal rate of 1 ")
                    .append(priceCurrency).append(" = ").append(fx.stripTrailingZeros().toPlainString())
                    .append(' ').append(account.getCurrency()).append('.');
        }

        return new PositionSizeResponse(
                portfolioValue,
                entry,
                stop,
                target,
                riskPerUnit,
                scale(percentOf(riskPerUnit, entry)),
                capitalAtRisk,
                quantity,
                notional,
                scale(allocationPercent),
                residualRisk,
                rewardPerUnit,
                rr,
                potentialProfit,
                potentialLoss,
                breakEven,
                maxByAllocation,
                maxByCapital,
                rationale.toString(),
                DISCLAIMER,
                priceCurrency,
                account.getCurrency(),
                fx);
    }

    // =======================================================================================
    // Journal
    // =======================================================================================

    @Transactional(readOnly = true)
    public Page<JournalEntryDto> journal(Long userId, int page, int size) {
        TradingAccount account = requireAccount(userId);
        return journalRepository.findByAccountIdOrderByCreatedAtDesc(account.getId(),
                        PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 100)))
                .map(this::toDto);
    }

    @Transactional
    public JournalEntryDto addJournal(Long userId, CreateJournalEntryRequest request) {
        TradingAccount account = requireAccount(userId);
        JournalEntry entry = new JournalEntry();
        entry.setAccountId(account.getId());
        entry.setTitle(request.title().trim());
        entry.setNote(request.note());
        entry.setTag(request.tag());
        entry.setRiskAmount(request.riskAmount() == null ? null : Decimals.money(request.riskAmount()));
        entry.setRewardAmount(request.rewardAmount() == null ? null : Decimals.money(request.rewardAmount()));
        entry.setOrderId(request.orderId());
        if (request.symbol() != null && !request.symbol().isBlank()) {
            entry.setInstrumentId(instrumentService.require(request.symbol()).getId());
        }
        entry.setCreatedAt(java.time.Instant.now());
        return toDto(journalRepository.save(entry));
    }

    @Transactional
    public void deleteJournal(Long userId, Long id) {
        TradingAccount account = requireAccount(userId);
        JournalEntry entry = journalRepository.findById(id)
                .filter(candidate -> candidate.getAccountId().equals(account.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("JOURNAL_NOT_FOUND",
                        "Journal entry " + id + " was not found."));
        journalRepository.delete(entry);
    }

    private JournalEntryDto toDto(JournalEntry entry) {
        String symbol = entry.getInstrumentId() == null ? null
                : instrumentService.find(entry.getInstrumentId()).map(i -> i.getSymbol()).orElse(null);
        BigDecimal rr = null;
        if (entry.getRiskAmount() != null && entry.getRewardAmount() != null
                && entry.getRiskAmount().signum() > 0) {
            rr = entry.getRewardAmount().divide(entry.getRiskAmount(), 4, RoundingMode.HALF_UP);
        }
        return new JournalEntryDto(entry.getId(), entry.getInstrumentId(), symbol, entry.getOrderId(),
                entry.getTitle(), entry.getNote(), entry.getTag(), entry.getRiskAmount(),
                entry.getRewardAmount(), rr, entry.getCreatedAt());
    }

    // ---------------------------------------------------------------------------------------

    private TradingAccount requireAccount(Long userId) {
        return accountRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("ACCOUNT_NOT_FOUND",
                        "No trading account exists for this user."));
    }

    private static BigDecimal percentOf(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return part.multiply(Decimals.HUNDRED, Decimals.MC).divide(whole, Decimals.MC);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimalsQty(BigDecimal value) {
        return value.setScale(Decimals.QTY_SCALE, RoundingMode.DOWN);
    }

    /** Tiny helper so null-safety reads clearly at the call sites. */
    private static final class DecimalSafe {
        private DecimalSafe() {
        }

        static BigDecimal max(BigDecimal a, BigDecimal b) {
            return a.compareTo(b) >= 0 ? a : b;
        }
    }
}

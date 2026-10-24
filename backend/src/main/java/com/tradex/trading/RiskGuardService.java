package com.tradex.trading;

import com.tradex.entity.TradingAccount;
import com.tradex.exception.TradingBlockedException;
import com.tradex.repository.OrderRepository;
import com.tradex.repository.TradeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Enforces the account's simulated risk guards before any order is accepted.
 *
 * <h2>Daily loss limit</h2>
 * When the guard is enabled, the engine compares the day's <em>net</em> result against the configured
 * limit. Once breached the account's {@code lossLimitBreachDate} is stamped with the trading date, and
 * every subsequent order is refused until either:
 * <ul>
 *   <li>the next trading day begins (the stamp no longer matches today), or</li>
 *   <li>the user restarts the simulated account ({@code POST /api/account/reset}), or</li>
 *   <li>the user explicitly clears the breach from the Risk console.</li>
 * </ul>
 *
 * <p>Today's result is defined as realised P&amp;L booked today <em>plus</em> the marked-to-market
 * move of open positions since their start-of-day reference. The reference is the position's own
 * average cost when it was opened today, otherwise the previous session's close. This is the same
 * approximation a broker statement makes when a carried position is added to intraday.</p>
 */
@Service
public class RiskGuardService {

    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final TradingClock clock;

    public RiskGuardService(TradeRepository tradeRepository, OrderRepository orderRepository, TradingClock clock) {
        this.tradeRepository = tradeRepository;
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    /** Realised P&amp;L booked since the start of the current trading day. */
    @Transactional(readOnly = true)
    public BigDecimal realizedPnlToday(Long accountId) {
        return Decimals.money(Decimals.nullToZero(
                tradeRepository.sumRealizedPnlSince(accountId, clock.startOfToday())));
    }

    /** Charges paid since the start of the current trading day. */
    @Transactional(readOnly = true)
    public BigDecimal chargesToday(Long accountId) {
        return Decimals.money(Decimals.nullToZero(
                tradeRepository.sumChargesSince(accountId, clock.startOfToday())));
    }

    @Transactional(readOnly = true)
    public int tradesToday(Long accountId) {
        return (int) tradeRepository.countSince(accountId, clock.startOfToday());
    }

    @Transactional(readOnly = true)
    public int ordersToday(Long accountId) {
        return (int) orderRepository.countByAccountId(accountId);
    }

    /**
     * Throws when the account may not trade right now. Marks the breach date as a side effect so the
     * block survives until the next trading day or an explicit reset.
     */
    public void enforceCanTrade(TradingAccount account, BigDecimal todayNetPnl) {
        if (account.isTradingBlocked()) {
            throw new TradingBlockedException(blockedReason(account, todayNetPnl));
        }
        if (!account.isDailyLossLimitEnabled()) {
            return;
        }
        BigDecimal limit = Decimals.nullToZero(account.getDailyLossLimit());
        if (limit.signum() <= 0) {
            return;
        }
        BigDecimal loss = todayNetPnl.negate();
        if (loss.compareTo(limit) < 0) {
            return;
        }
        LocalDate today = clock.today();
        if (account.getLossLimitBreachDate() == null || !account.getLossLimitBreachDate().equals(today)) {
            account.setLossLimitBreachDate(today);
        }
        throw new TradingBlockedException(blockedReason(account, todayNetPnl));
    }

    /** {@code true} when the guard is currently tripped (read-only, no side effects). */
    public boolean isLimitBreached(TradingAccount account, BigDecimal todayNetPnl) {
        if (!account.isDailyLossLimitEnabled()) {
            return false;
        }
        BigDecimal limit = Decimals.nullToZero(account.getDailyLossLimit());
        if (limit.signum() <= 0) {
            return false;
        }
        if (todayNetPnl.negate().compareTo(limit) >= 0) {
            return true;
        }
        LocalDate breach = account.getLossLimitBreachDate();
        return breach != null && breach.equals(clock.today());
    }

    public String blockedReason(TradingAccount account, BigDecimal todayNetPnl) {
        StringBuilder builder = new StringBuilder();
        if (account.getLossLimitBreachDate() != null && account.getLossLimitBreachDate().equals(clock.today())) {
            builder.append("Daily loss limit of ")
                    .append(Decimals.nullToZero(account.getDailyLossLimit()).toPlainString())
                    .append(" was breached on ").append(account.getLossLimitBreachDate())
                    .append(" (today's net result: ").append(todayNetPnl.toPlainString()).append("). ")
                    .append("Reset the simulated account, clear the breach in the Risk console, ")
                    .append("or wait for the next trading day.");
        } else if (account.isTradingBlocked()) {
            builder.append("Trading is switched off for this account. ")
                    .append("Re-enable it from the Risk console.");
        } else {
            builder.append("Trading is temporarily blocked.");
        }
        return builder.toString();
    }

    /** Clears a breach so trading can resume without wiping the portfolio. */
    public void clearBreach(TradingAccount account) {
        account.setLossLimitBreachDate(null);
        account.setTradingBlocked(false);
    }

    public Instant startOfToday() {
        return clock.startOfToday();
    }
}

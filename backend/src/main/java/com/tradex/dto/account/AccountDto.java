package com.tradex.dto.account;

import java.math.BigDecimal;
import java.time.Instant;

/** Full snapshot of one virtual trading account, including the cash split. */
public record AccountDto(
        Long id,
        String accountNumber,
        String displayName,
        String currency,
        BigDecimal initialBalance,

        /** Spendable virtual cash right now. */
        BigDecimal availableCash,
        /** Cash parked behind resting buy orders. */
        BigDecimal reservedCash,
        /** availableCash + reservedCash -> the account's total virtual cash. */
        BigDecimal totalCash,

        BigDecimal holdingsValue,
        /** totalCash + holdingsValue -> the equity of the account. */
        BigDecimal portfolioValue,

        BigDecimal investedCapital,
        BigDecimal unrealizedPnl,
        BigDecimal unrealizedPnlPercent,
        BigDecimal realizedPnl,
        BigDecimal totalCharges,
        BigDecimal totalReturn,
        BigDecimal totalReturnPercent,
        BigDecimal todaysPnl,

        int openPositions,
        int openOrders,

        boolean dailyLossLimitEnabled,
        BigDecimal dailyLossLimit,
        boolean tradingBlocked,
        String tradingBlockedReason,

        int resetCount,
        Instant lastResetAt,
        Instant createdAt) {
}

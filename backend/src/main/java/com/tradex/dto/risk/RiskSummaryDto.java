package com.tradex.dto.risk;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Risk-management console payload. Educational only - never financial advice. */
public record RiskSummaryDto(
        String currency,

        BigDecimal portfolioValue,
        BigDecimal availableCash,
        BigDecimal investedValue,
        BigDecimal exposurePercent,

        BigDecimal todaysRealizedPnl,
        BigDecimal todaysUnrealizedPnl,
        BigDecimal todaysTotalPnl,
        BigDecimal todaysCharges,

        boolean dailyLossLimitEnabled,
        BigDecimal dailyLossLimit,
        BigDecimal dailyLossUsed,
        BigDecimal dailyLossRemaining,
        boolean limitBreached,
        boolean tradingBlocked,
        String tradingBlockedReason,

        int openPositionCount,
        BigDecimal totalOpenRisk,
        BigDecimal largestPositionPercent,
        String largestPositionSymbol,

        BigDecimal totalUnrealizedPnl,
        BigDecimal totalRealizedPnl,

        Map<String, BigDecimal> exposureByAssetClass,
        List<OpenPositionRisk> openPositions,
        /** Non-null when some open positions are priced in a currency other than {@link #currency}. */
        String fxNote,
        List<String> warnings,

        String disclaimer) {
}

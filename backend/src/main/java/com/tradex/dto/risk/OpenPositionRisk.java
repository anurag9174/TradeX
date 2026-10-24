package com.tradex.dto.risk;

import java.math.BigDecimal;

/** Per-position exposure used by the risk console. */
public record OpenPositionRisk(
        String symbol,
        String name,
        String assetClass,
        /** Quote currency of the instrument; the price fields below are printed in it. */
        String currency,

        BigDecimal quantity,
        BigDecimal avgCost,
        BigDecimal currentPrice,
        boolean priceAvailable,

        BigDecimal investedValue,
        BigDecimal marketValue,
        BigDecimal unrealizedPnl,
        BigDecimal unrealizedPnlPercent,

        BigDecimal allocationPercent,
        BigDecimal exposurePercent,

        BigDecimal stopLossPrice,
        BigDecimal targetPrice,
        /** Loss implied if the attached stop is hit. Null when no stop is set. */
        BigDecimal riskAmountAtStop,
        BigDecimal rewardAmountAtTarget,
        BigDecimal riskRewardRatio,
        /** Percent of the whole portfolio at risk if the attached stop is hit. */
        BigDecimal capitalAtRiskPercent,

        String riskNote) {
}

package com.tradex.dto.risk;

import java.math.BigDecimal;

public record PositionSizeResponse(
        BigDecimal portfolioValue,
        BigDecimal entryPrice,
        BigDecimal stopPrice,
        BigDecimal targetPrice,

        BigDecimal riskPerUnit,
        BigDecimal riskPercentOfPrice,
        BigDecimal capitalAtRisk,

        /** Suggested position size, floored to the instrument lot size. */
        BigDecimal suggestedQuantity,
        BigDecimal suggestedNotional,
        BigDecimal suggestedAllocationPercent,
        /** Notional left on the table purely because of lot rounding. */
        BigDecimal residualRiskBudget,

        BigDecimal rewardPerUnit,
        BigDecimal riskRewardRatio,

        BigDecimal potentialProfit,
        BigDecimal potentialLoss,
        BigDecimal breakEvenPrice,

        BigDecimal maxQuantityByAllocation,
        BigDecimal maxQuantityByCapital,
        String rationale,
        String disclaimer,

        /** Quote currency the price fields are printed in. */
        String priceCurrency,
        /** Currency the money amounts above are held in. */
        String ledgerCurrency,
        /** Ledger units per one unit of {@code priceCurrency}; 1 when no conversion applies. */
        BigDecimal fxRate) {
}

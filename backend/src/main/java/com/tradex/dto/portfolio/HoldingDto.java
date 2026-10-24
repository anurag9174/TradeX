package com.tradex.dto.portfolio;

import java.math.BigDecimal;
import java.time.Instant;

/** One line of the portfolio table. */
public record HoldingDto(
        Long id,
        String symbol,
        String name,
        String exchange,
        String assetClass,
        String currency,

        BigDecimal quantity,
        BigDecimal avgCost,
        BigDecimal currentPrice,
        boolean priceAvailable,

        BigDecimal investedValue,
        BigDecimal marketValue,
        BigDecimal unrealizedPnl,
        BigDecimal unrealizedPnlPercent,
        BigDecimal realizedPnl,

        BigDecimal dayChange,
        BigDecimal dayChangePercent,
        BigDecimal dayPnl,

        BigDecimal allocationPercent,

        BigDecimal stopLossPrice,
        BigDecimal targetPrice,
        BigDecimal riskRewardRatio,

        Instant openedAt,
        Instant lastTradedAt) {
}

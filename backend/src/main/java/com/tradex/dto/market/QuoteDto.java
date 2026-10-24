package com.tradex.dto.market;

import com.tradex.enums.AssetClass;
import com.tradex.enums.MarketDataSource;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A point-in-time quote.
 *
 * <p>{@code dataSource} is part of the payload on purpose: the UI must never be able to render a
 * simulated price without labelling it.</p>
 */
public record QuoteDto(
        String symbol,
        String name,
        String exchange,
        AssetClass assetClass,
        String currency,

        BigDecimal lastPrice,
        BigDecimal previousClose,
        BigDecimal change,
        BigDecimal changePercent,
        BigDecimal dayOpen,
        BigDecimal dayHigh,
        BigDecimal dayLow,
        BigDecimal dayVolume,

        BigDecimal bid,
        BigDecimal ask,
        boolean tradable,

        MarketDataSource dataSource,
        String dataSourceLabel,
        String provider,
        boolean priceAvailable,
        String unavailableReason,

        Instant asOf) {
}

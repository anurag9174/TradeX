package com.tradex.dto.market;

import com.tradex.enums.MarketDataSource;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One live tick pushed over the {@code /topic/quotes} STOMP destination.
 *
 * <p>{@code source} travels with every tick so the client can keep its SIMULATED / LIVE badge
 * truthful without a second request.</p>
 */
public record QuoteTickDto(
        String symbol,
        /** Quote currency of the instrument; {@code lastPrice} is printed in it. */
        String currency,
        BigDecimal lastPrice,
        BigDecimal previousClose,
        BigDecimal change,
        BigDecimal changePercent,
        BigDecimal dayHigh,
        BigDecimal dayLow,
        BigDecimal dayVolume,
        boolean available,
        MarketDataSource source,
        String sourceLabel,
        Instant asOf) {
}

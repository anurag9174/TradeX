package com.tradex.marketdata;

import com.tradex.enums.MarketDataSource;

import java.math.BigDecimal;
import java.time.Instant;

/** Immutable provider-agnostic quote snapshot. */
public record QuoteSnapshot(
        String symbol,
        BigDecimal lastPrice,
        BigDecimal previousClose,
        BigDecimal dayOpen,
        BigDecimal dayHigh,
        BigDecimal dayLow,
        BigDecimal dayVolume,
        BigDecimal bid,
        BigDecimal ask,
        MarketDataSource source,
        Instant asOf) {

    /** Absolute change against the previous close. */
    public BigDecimal change() {
        if (lastPrice == null || previousClose == null) {
            return BigDecimal.ZERO;
        }
        return lastPrice.subtract(previousClose);
    }

    /** Percentage change against the previous close. */
    public BigDecimal changePercent() {
        if (lastPrice == null || previousClose == null || previousClose.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return lastPrice.subtract(previousClose)
                .multiply(BigDecimal.valueOf(100))
                .divide(previousClose, 6, java.math.RoundingMode.HALF_UP);
    }

    public static QuoteSnapshot unavailable(String symbol, MarketDataSource source) {
        return new QuoteSnapshot(symbol, null, null, null, null, null, BigDecimal.ZERO,
                null, null, source, Instant.now());
    }
}

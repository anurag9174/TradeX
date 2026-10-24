package com.tradex.marketdata;

import java.math.BigDecimal;
import java.time.Instant;

/** Immutable OHLCV bar returned by a provider. {@code openTime} is the candle's open boundary. */
public record CandleBar(
        Instant openTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume) {
}

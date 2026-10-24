package com.tradex.dto.market;

import com.tradex.enums.MarketDataSource;
import com.tradex.enums.Timeframe;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * OHLCV series for one instrument / timeframe, in the shape Lightweight Charts expects
 * ({@code time} as epoch seconds, ascending).
 */
public record CandleSeriesDto(
        String symbol,
        String name,
        /** Quote currency of the instrument; every OHLC value is printed in it. */
        String currency,
        Timeframe timeframe,
        String timeframeCode,
        List<CandleDto> candles,
        MarketDataSource dataSource,
        String dataSourceLabel,
        boolean live,
        /**
         * Human-readable provenance of the series: which provider produced it, or why it could not
         * be produced. {@code null} when there is nothing special to say.
         */
        String note,
        Instant generatedAt) {

    public record CandleDto(
            long time,
            Instant openTime,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal volume) {
    }
}

package com.tradex.dto.market;

import com.tradex.enums.MarketDataSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Indicator overlays computed server-side from the very same candle series the chart renders.
 *
 * <p>{@code times} is the shared x-axis (epoch seconds, ascending) and every series in
 * {@code series} is index-aligned with it, including {@code null} holes for warm-up periods. The
 * client therefore never has to align or interpolate anything.</p>
 */
public record IndicatorSetDto(
        String symbol,
        String name,
        String timeframeCode,
        int candleCount,
        List<Long> times,
        Map<String, Series> series,
        MarketDataSource dataSource,
        String dataSourceLabel,
        Instant generatedAt) {

    /**
     * One overlay.
     *
     * @param pane      {@code price} (drawn over the candles), {@code rsi} or {@code macd} (own pane)
     * @param kind      {@code line}, {@code band} or {@code macd}
     * @param values    the main line. For bands this is the upper band.
     * @param upper     upper band (Bollinger only)
     * @param middle    middle band (Bollinger only)
     * @param lower     lower band (Bollinger only)
     * @param signal    signal line (MACD only)
     * @param histogram MACD histogram
     */
    public record Series(
            String key,
            String label,
            String pane,
            String kind,
            Map<String, Object> params,
            List<BigDecimal> values,
            List<BigDecimal> upper,
            List<BigDecimal> middle,
            List<BigDecimal> lower,
            List<BigDecimal> signal,
            List<BigDecimal> histogram) {
    }
}

package com.tradex.marketdata;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Indicators must be derived from the actual close series - never invented, never forward-filled
 * where the maths has no value yet.
 */
class IndicatorCalculatorTest {

    private static List<BigDecimal> closes(String... values) {
        return List.of(values).stream().map(BigDecimal::new).toList();
    }

    // =======================================================================================
    // SMA
    // =======================================================================================

    @Test
    void smaLeavesTheWarmUpWindowEmptyAndAveragesExactly() {
        List<BigDecimal> out = IndicatorCalculator.sma(closes("1", "2", "3", "4", "5"), 3);

        assertThat(out).hasSize(5);
        assertThat(out.get(0)).isNull();
        assertThat(out.get(1)).isNull();
        assertThat(out.get(2)).isEqualByComparingTo("2");
        assertThat(out.get(3)).isEqualByComparingTo("3");
        assertThat(out.get(4)).isEqualByComparingTo("4");
    }

    @Test
    void smaReturnsAllNullsWhenTheSeriesIsShorterThanThePeriod() {
        List<BigDecimal> out = IndicatorCalculator.sma(closes("10", "11"), 5);
        assertThat(out).containsOnlyNulls();
    }

    // =======================================================================================
    // EMA
    // =======================================================================================

    @Test
    void emaOfAConstantSeriesIsThatConstant() {
        List<BigDecimal> out = IndicatorCalculator.ema(closes("5", "5", "5", "5", "5", "5"), 3);

        assertThat(out.get(0)).isNull();
        assertThat(out.get(1)).isNull();
        for (int i = 2; i < out.size(); i++) {
            assertThat(out.get(i)).isEqualByComparingTo("5");
        }
    }

    @Test
    void emaReactsFasterThanSmaImmediatelyAfterAPricePop() {
        List<BigDecimal> series = closes("10", "10", "10", "10", "10", "20", "20", "20", "20", "20");
        List<BigDecimal> sma = IndicatorCalculator.sma(series, 5);
        List<BigDecimal> ema = IndicatorCalculator.ema(series, 5);

        // Right after the pop the EMA is already closer to the new price than the SMA, which only
        // slowly flushes the old value out of its window. Both still lag the new level entirely.
        int firstAfterPop = 5;
        assertThat(ema.get(firstAfterPop)).isGreaterThan(sma.get(firstAfterPop));
        assertThat(ema.get(firstAfterPop)).isLessThan(new BigDecimal("20"));
        assertThat(sma.get(firstAfterPop)).isEqualByComparingTo("12");
    }

    // =======================================================================================
    // RSI
    // =======================================================================================

    @Test
    void rsiIsHundredWhenEveryChangeIsAGain() {
        List<BigDecimal> out = IndicatorCalculator.rsi(
                closes("10", "11", "12", "13", "14", "15", "16"), 6);

        assertThat(out.get(6)).isEqualByComparingTo("100");
    }

    @Test
    void rsiIsZeroWhenEveryChangeIsALoss() {
        List<BigDecimal> out = IndicatorCalculator.rsi(
                closes("16", "15", "14", "13", "12", "11", "10"), 6);

        assertThat(out.get(6)).isEqualByComparingTo("0");
    }

    @Test
    void rsiOfAFlatSeriesIsFifty() {
        List<BigDecimal> out = IndicatorCalculator.rsi(
                closes("7", "7", "7", "7", "7", "7", "7"), 6);

        assertThat(out.get(6)).isEqualByComparingTo("50");
    }

    @Test
    void rsiNeverLeavesTheZeroToHundredBand() {
        List<BigDecimal> series = closes(
                "100", "102", "101", "105", "99", "103", "107", "104", "108", "110",
                "105", "101", "98", "102", "106", "109", "111", "108", "112", "115");
        List<BigDecimal> out = IndicatorCalculator.rsi(series, 14);

        out.stream().filter(value -> value != null).forEach(value -> {
            assertThat(value).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(value).isLessThanOrEqualTo(new BigDecimal("100"));
        });
    }

    // =======================================================================================
    // MACD
    // =======================================================================================

    @Test
    void macdOfAConstantSeriesIsZeroEverywhereAndHistogramAgrees() {
        List<BigDecimal> series = closes(
                "50", "50", "50", "50", "50", "50", "50", "50", "50", "50",
                "50", "50", "50", "50", "50", "50", "50", "50", "50", "50",
                "50", "50", "50", "50", "50", "50", "50", "50", "50", "50",
                "50", "50", "50", "50", "50", "50");
        IndicatorCalculator.MacdResult result = IndicatorCalculator.macd(series, 12, 26, 9);

        int last = series.size() - 1;
        assertThat(result.macdLine().get(last)).isEqualByComparingTo("0");
        assertThat(result.signalLine().get(last)).isEqualByComparingTo("0");
        assertThat(result.histogram().get(last)).isEqualByComparingTo("0");
    }

    @Test
    void macdGoesPositiveAfterTheSeriesTurnsUp() {
        List<BigDecimal> series = closes(
                "100", "100", "100", "100", "100", "100", "100", "100", "100", "100",
                "100", "100", "100", "100", "100", "100", "100", "100", "100", "100",
                "100", "100", "100", "100", "100", "100", "110", "120", "130", "140",
                "150", "160", "170", "180", "190", "200");
        IndicatorCalculator.MacdResult result = IndicatorCalculator.macd(series, 12, 26, 9);

        int last = series.size() - 1;
        assertThat(result.macdLine().get(last)).isGreaterThan(BigDecimal.ZERO);
        assertThat(result.histogram().get(last)).isGreaterThan(BigDecimal.ZERO);
    }

    // =======================================================================================
    // Bollinger
    // =======================================================================================

    @Test
    void bollingerCollapsesToTheConstantWhenThereIsNoVariance() {
        List<BigDecimal> series = closes("8", "8", "8", "8", "8", "8", "8");
        IndicatorCalculator.BollingerResult bands = IndicatorCalculator.bollinger(series, 5, new BigDecimal("2"));

        int last = series.size() - 1;
        assertThat(bands.middle().get(last)).isEqualByComparingTo("8");
        assertThat(bands.upper().get(last)).isEqualByComparingTo("8");
        assertThat(bands.lower().get(last)).isEqualByComparingTo("8");
    }

    @Test
    void bollingerWrapsTheMiddleBandAroundRealDispersion() {
        List<BigDecimal> series = closes("10", "12", "11", "13", "12", "14");
        IndicatorCalculator.BollingerResult bands = IndicatorCalculator.bollinger(series, 4, new BigDecimal("2"));

        int last = series.size() - 1;
        assertThat(bands.upper().get(last)).isGreaterThan(bands.middle().get(last));
        assertThat(bands.middle().get(last)).isGreaterThan(bands.lower().get(last));
        // Middle band is the SMA of the window.
        assertThat(bands.middle().get(last)).isEqualByComparingTo("12.5");
    }
}

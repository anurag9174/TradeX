package com.tradex.marketdata;

import com.tradex.trading.Decimals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure technical-analysis maths.
 *
 * <p>Every function takes a close series and returns a list of exactly the same length, using
 * {@code null} for the warm-up period. That alignment guarantee is what lets the frontend plot an
 * indicator straight onto the candle array without index arithmetic.</p>
 *
 * <p>Definitions used (all on closes, in order):</p>
 * <ul>
 *   <li><b>SMA(n)</b> - arithmetic mean of the last {@code n} closes.</li>
 *   <li><b>EMA(n)</b> - multiplier {@code k = 2/(n+1)}; seeded with the SMA of the first
 *       {@code n} values (the standard TA convention).</li>
 *   <li><b>RSI(n)</b> - Wilder smoothing: {@code avgGain}/{@code avgLoss} are seeded with a simple
 *       mean of the first {@code n} changes and then smoothed with
 *       {@code (prev*(n-1) + current)/n}.</li>
 *   <li><b>MACD</b> - {@code EMA(fast) - EMA(slow)}, signal = EMA(signalLength) of that line,
 *       histogram = MACD - signal.</li>
 *   <li><b>Bollinger</b> - SMA(n) +/- {@code mult} * <em>population</em> standard deviation of the
 *       same window.</li>
 * </ul>
 *
 * <p>All maths is performed with {@link BigDecimal} so a value never depends on floating-point
 * ordering.</p>
 */
public final class IndicatorCalculator {

    public static final int SCALE = 8;

    private IndicatorCalculator() {
    }

    public static List<BigDecimal> sma(List<BigDecimal> closes, int period) {
        List<BigDecimal> out = nullList(closes.size());
        if (period <= 0 || closes.size() < period) {
            return out;
        }
        BigDecimal windowSum = BigDecimal.ZERO;
        for (int i = 0; i < closes.size(); i++) {
            windowSum = windowSum.add(nz(closes.get(i)));
            if (i >= period) {
                windowSum = windowSum.subtract(nz(closes.get(i - period)));
            }
            if (i >= period - 1) {
                out.set(i, windowSum.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP));
            }
        }
        return out;
    }

    public static List<BigDecimal> ema(List<BigDecimal> closes, int period) {
        List<BigDecimal> out = nullList(closes.size());
        if (period <= 0 || closes.size() < period) {
            return out;
        }
        // Seed with the SMA of the first `period` closes.
        BigDecimal seed = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            seed = seed.add(nz(closes.get(i)));
        }
        seed = seed.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
        out.set(period - 1, seed);

        BigDecimal k = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period + 1L), SCALE, RoundingMode.HALF_UP);
        BigDecimal previous = seed;
        for (int i = period; i < closes.size(); i++) {
            BigDecimal current = nz(closes.get(i));
            BigDecimal next = current.multiply(k, Decimals.MC)
                    .add(previous.multiply(BigDecimal.ONE.subtract(k), Decimals.MC), Decimals.MC);
            next = next.setScale(SCALE, RoundingMode.HALF_UP);
            out.set(i, next);
            previous = next;
        }
        return out;
    }

    public static List<BigDecimal> rsi(List<BigDecimal> closes, int period) {
        List<BigDecimal> out = nullList(closes.size());
        if (period <= 0 || closes.size() <= period) {
            return out;
        }
        BigDecimal gainSum = BigDecimal.ZERO;
        BigDecimal lossSum = BigDecimal.ZERO;
        for (int i = 1; i <= period; i++) {
            BigDecimal change = nz(closes.get(i)).subtract(nz(closes.get(i - 1)));
            if (change.signum() > 0) {
                gainSum = gainSum.add(change);
            } else {
                lossSum = lossSum.add(change.abs());
            }
        }
        BigDecimal avgGain = gainSum.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
        BigDecimal avgLoss = lossSum.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
        out.set(period, rsiFrom(avgGain, avgLoss));

        BigDecimal n = BigDecimal.valueOf(period);
        for (int i = period + 1; i < closes.size(); i++) {
            BigDecimal change = nz(closes.get(i)).subtract(nz(closes.get(i - 1)));
            BigDecimal gain = change.signum() > 0 ? change : BigDecimal.ZERO;
            BigDecimal loss = change.signum() < 0 ? change.abs() : BigDecimal.ZERO;
            avgGain = avgGain.multiply(n.subtract(BigDecimal.ONE), Decimals.MC)
                    .add(gain, Decimals.MC).divide(n, SCALE, RoundingMode.HALF_UP);
            avgLoss = avgLoss.multiply(n.subtract(BigDecimal.ONE), Decimals.MC)
                    .add(loss, Decimals.MC).divide(n, SCALE, RoundingMode.HALF_UP);
            out.set(i, rsiFrom(avgGain, avgLoss));
        }
        return out;
    }

    private static BigDecimal rsiFrom(BigDecimal avgGain, BigDecimal avgLoss) {
        if (avgLoss.signum() == 0) {
            return avgGain.signum() == 0
                    ? BigDecimal.valueOf(50).setScale(SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.valueOf(100).setScale(SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal rs = avgGain.divide(avgLoss, SCALE, RoundingMode.HALF_UP);
        BigDecimal rsi = BigDecimal.valueOf(100)
                .subtract(BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), SCALE, RoundingMode.HALF_UP));
        return rsi.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** @return {@code [macdLine, signalLine, histogram]} aligned to the input length. */
    public static MacdResult macd(List<BigDecimal> closes, int fast, int slow, int signalLength) {
        List<BigDecimal> fastEma = ema(closes, fast);
        List<BigDecimal> slowEma = ema(closes, slow);
        List<BigDecimal> macdLine = nullList(closes.size());
        for (int i = 0; i < closes.size(); i++) {
            BigDecimal f = fastEma.get(i);
            BigDecimal s = slowEma.get(i);
            if (f != null && s != null) {
                macdLine.set(i, f.subtract(s).setScale(SCALE, RoundingMode.HALF_UP));
            }
        }
        List<BigDecimal> signal = emaOfSparse(macdLine, signalLength);
        List<BigDecimal> histogram = nullList(closes.size());
        for (int i = 0; i < closes.size(); i++) {
            BigDecimal m = macdLine.get(i);
            BigDecimal s = signal.get(i);
            if (m != null && s != null) {
                histogram.set(i, m.subtract(s).setScale(SCALE, RoundingMode.HALF_UP));
            }
        }
        return new MacdResult(macdLine, signal, histogram);
    }

    /** @return {@code [middle, upper, lower]} aligned to the input length. */
    public static BollingerResult bollinger(List<BigDecimal> closes, int period, BigDecimal multiplier) {
        List<BigDecimal> middle = sma(closes, period);
        List<BigDecimal> upper = nullList(closes.size());
        List<BigDecimal> lower = nullList(closes.size());
        if (period <= 0 || closes.size() < period) {
            return new BollingerResult(middle, upper, lower);
        }
        for (int i = period - 1; i < closes.size(); i++) {
            BigDecimal mean = middle.get(i);
            if (mean == null) {
                continue;
            }
            BigDecimal sumSquares = BigDecimal.ZERO;
            for (int j = i - period + 1; j <= i; j++) {
                BigDecimal deviation = nz(closes.get(j)).subtract(mean);
                sumSquares = sumSquares.add(deviation.multiply(deviation, Decimals.MC), Decimals.MC);
            }
            BigDecimal variance = sumSquares.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
            BigDecimal sd = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal band = sd.multiply(multiplier, Decimals.MC).setScale(SCALE, RoundingMode.HALF_UP);
            upper.set(i, mean.add(band).setScale(SCALE, RoundingMode.HALF_UP));
            lower.set(i, mean.subtract(band).setScale(SCALE, RoundingMode.HALF_UP));
        }
        return new BollingerResult(middle, upper, lower);
    }

    /** EMA over a series that starts with nulls; the EMA only begins once `period` real values exist. */
    private static List<BigDecimal> emaOfSparse(List<BigDecimal> values, int period) {
        List<BigDecimal> out = nullList(values.size());
        if (period <= 0) {
            return out;
        }
        int firstReal = -1;
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) != null) {
                firstReal = i;
                break;
            }
        }
        if (firstReal < 0) {
            return out;
        }
        List<BigDecimal> compact = new ArrayList<>();
        for (int i = firstReal; i < values.size(); i++) {
            compact.add(values.get(i));
        }
        List<BigDecimal> compactEma = ema(compact, period);
        for (int i = 0; i < compactEma.size(); i++) {
            if (compactEma.get(i) != null) {
                out.set(firstReal + i, compactEma.get(i));
            }
        }
        return out;
    }

    private static List<BigDecimal> nullList(int size) {
        List<BigDecimal> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(null);
        }
        return list;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record MacdResult(List<BigDecimal> macdLine, List<BigDecimal> signalLine, List<BigDecimal> histogram) {
    }

    public record BollingerResult(List<BigDecimal> middle, List<BigDecimal> upper, List<BigDecimal> lower) {
    }
}

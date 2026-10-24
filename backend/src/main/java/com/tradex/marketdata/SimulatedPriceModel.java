package com.tradex.marketdata;

import com.tradex.entity.Instrument;
import com.tradex.trading.Decimals;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic synthetic price process used when no live market-data provider is configured.
 *
 * <h2>Why deterministic?</h2>
 * The price of an instrument at any instant is a <em>pure function</em> of
 * {@code (instrument, absolute time)}. The same wall-clock second therefore yields the same price
 * on every machine, on every restart, and for every user - and a candle generated at boot can be
 * regenerated identically later. Nothing about the series depends on when the JVM happened to
 * start, which makes P&amp;L reproducible and the whole engine unit-testable.
 *
 * <h2>The model</h2>
 * Log-price deviates from the instrument's anchor price by a sum of two fractal-noise (fBm)
 * components:
 * <ul>
 *   <li>a <b>macro</b> component on a ~1-day scale, producing multi-week trends, and</li>
 *   <li>a <b>micro</b> component at minute scale, producing the intraday jitter.</li>
 * </ul>
 * The combined deviation is multiplied by a per-instrument gain that is calibrated empirically so
 * the realised standard deviation of one-day log returns matches {@code instrument.volatility}.
 * No drift term is applied: simulated prices are unbiased, so results reflect the trader's
 * decisions rather than a favourable tape.
 *
 * <h2>What this is not</h2>
 * These are not market prices and must never be presented as such. Every payload that carries a
 * simulated price is tagged {@code SIMULATED} from here to the UI badge.
 */
@Component
public class SimulatedPriceModel {

    /** Fixed anchor for the time axis. Changing it regenerates the entire simulated history. */
    public static final Instant SIMULATION_EPOCH = Instant.parse("2024-01-01T00:00:00Z");

    private static final double MINUTES_PER_DAY = 1440.0;

    /** Weight of the slow, multi-week component before calibration. */
    private static final double MACRO_WEIGHT = 1.00;
    /** Weight of the fast, intraday component before calibration. */
    private static final double MICRO_WEIGHT = 0.55;

    /** Cache of calibration gains keyed by instrument id. */
    private final ConcurrentHashMap<Long, Double> gainCache = new ConcurrentHashMap<>();

    /** Cache of the anchor price offsets so two instruments never move in lockstep. */
    private final ConcurrentHashMap<Long, Long> seedCache = new ConcurrentHashMap<>();

    private final AtomicLong evaluations = new AtomicLong();

    // ---------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------

    /** Raw (unrounded) simulated price at a given instant. */
    public BigDecimal priceAt(Instrument instrument, Instant when) {
        double raw = rawPrice(instrument, when.toEpochMilli() / 1000.0);
        // Simulated crypto trades to more decimals than an equity tick grid, so keep the extra
        // precision and let the caller snap to the instrument's tick size.
        return BigDecimal.valueOf(raw).setScale(Decimals.PRICE_SCALE, java.math.RoundingMode.HALF_UP);
    }

    /** Price snapped to the instrument's tick grid - the value the engine would actually fill at. */
    public BigDecimal tickAlignedPrice(Instrument instrument, Instant when) {
        return Decimals.toTick(priceAt(instrument, when), instrument.getTickSize());
    }

    /**
     * Fractional daily volatility actually realised by the model for this instrument, measured on
     * a fixed reference window. Useful for diagnostics and for sizing the simulated volume.
     */
    public double realisedDailyVolatility(Instrument instrument) {
        Double gain = gainFor(instrument);
        return gain * referenceDailyDeviation(seedOf(instrument));
    }

    /**
     * Simulated traded volume for the candle starting at {@code candleStart}. Deterministic in the
     * instrument, the candle length and the period, and correlated with volatility so busy periods
     * look busy.
     */
    public BigDecimal volumeFor(Instrument instrument, Instant candleStart, long periodSeconds) {
        long bucket = candleStart.getEpochSecond() / Math.max(1, periodSeconds);
        long seed = seedOf(instrument) ^ 0x5DEECE66DL;
        double base = 0.5 + 0.5 * Math.abs(noise(seed, bucket * 0.37));
        double volatilityFactor = instrument.getVolatility().doubleValue() * 100.0;
        double periodFactor = Math.sqrt(periodSeconds / 60.0);
        double lots = base * volatilityFactor * periodFactor * 900.0;
        return Decimals.money(BigDecimal.valueOf(Math.max(1.0, lots)));
    }

    /** Number of price evaluations performed since start-up (diagnostics / tests). */
    public long evaluationCount() {
        return evaluations.get();
    }

    // ---------------------------------------------------------------------------------------
    // Core maths
    // ---------------------------------------------------------------------------------------

    private double rawPrice(Instrument instrument, double epochSeconds) {
        evaluations.incrementAndGet();
        long seed = seedOf(instrument);
        double minutes = (epochSeconds - SIMULATION_EPOCH.getEpochSecond()) / 60.0;

        double macro = fbm(seed, minutes / MINUTES_PER_DAY, 7, 2.0, 0.55);
        double micro = fbm(seed + 0x9E3779B9L, minutes, 5, 2.3, 0.50);
        double deviation = MACRO_WEIGHT * macro + MICRO_WEIGHT * micro;

        double gain = gainFor(instrument);
        double anchor = instrument.getSeedPrice().doubleValue();

        // Gentle mean reversion keeps a long-running simulated series from drifting into
        // implausible territory (e.g. a simulated equity reaching 1e9 after a year of uptime).
        double logPrice = Math.log(anchor) + gain * deviation;
        double price = Math.exp(logPrice);

        // Hard sanity band: +/- 85% around the anchor. This is a guard rail, not a trading rule.
        double floor = anchor * 0.15;
        double ceiling = anchor * 2.60;
        if (price < floor) {
            price = floor;
        } else if (price > ceiling) {
            price = ceiling;
        }
        return price;
    }

    private long seedOf(Instrument instrument) {
        long key = instrument.getId() == null ? instrument.getSymbol().hashCode() : instrument.getId();
        return seedCache.computeIfAbsent(key, k -> {
            long s = mix64(k * 0x9E3779B97F4A7C15L + instrument.getSymbol().hashCode());
            return s | 1L;
        });
    }

    /**
     * Calibrates the gain so one-day realised volatility matches the instrument's configured
     * {@code volatility}. Computed once per instrument from a fixed reference grid, therefore
     * deterministic.
     */
    private double gainFor(Instrument instrument) {
        long key = instrument.getId() == null ? instrument.getSymbol().hashCode() : instrument.getId();
        return gainCache.computeIfAbsent(key, k -> {
            double target = instrument.getVolatility().doubleValue();
            if (target <= 0) {
                return 0.0;
            }
            long seed = seedOf(instrument);
            double reference = referenceDailyDeviation(seed);
            if (reference <= 1e-12) {
                // Degenerate noise; fall back to a plain sqrt-time scaling.
                return target / Math.sqrt(1.0 / MINUTES_PER_DAY);
            }
            return target / reference;
        });
    }

    /**
     * Standard deviation of one-day log deviations for the given seed, measured on a 5-minute grid
     * over a 288-day window (288 samples of one-day increments).
     */
    private double referenceDailyDeviation(long seed) {
        final int stepsPerDay = 288;
        final int days = 288;
        double previous = deviationAt(seed, 0.0);
        double sum = 0;
        double sumSq = 0;
        // Accumulate intraday increments so the micro component is fully captured.
        for (int d = 0; d < days; d++) {
            double base = d * MINUTES_PER_DAY;
            double daily = 0;
            for (int s = 1; s <= stepsPerDay; s++) {
                double current = deviationAt(seed, base + s * (MINUTES_PER_DAY / stepsPerDay));
                daily += current - previous;
                previous = current;
            }
            sum += daily;
            sumSq += daily * daily;
        }
        double mean = sum / days;
        double variance = (sumSq / days) - mean * mean;
        return variance <= 0 ? 1e-6 : Math.sqrt(variance);
    }

    private double deviationAt(long seed, double minutes) {
        double macro = fbm(seed, minutes / MINUTES_PER_DAY, 7, 2.0, 0.55);
        double micro = fbm(seed + 0x9E3779B9L, minutes, 5, 2.3, 0.50);
        return MACRO_WEIGHT * macro + MICRO_WEIGHT * micro;
    }

    // ---------------------------------------------------------------------------------------
    // Value noise / fractal Brownian motion primitives
    // ---------------------------------------------------------------------------------------

    /** Fractal Brownian motion built from value noise. Range is approximately [-1, 1]. */
    static double fbm(long seed, double x, int octaves, double lacunarity, double gain) {
        double sum = 0;
        double amplitude = 1;
        double frequency = 1;
        double normaliser = 0;
        for (int o = 0; o < octaves; o++) {
            sum += amplitude * noise(seed + o * 0x27D4EB2FL, x * frequency);
            normaliser += amplitude;
            amplitude *= gain;
            frequency *= lacunarity;
        }
        return normaliser == 0 ? 0 : sum / normaliser;
    }

    /** Smooth value noise interpolating a hashed lattice onto [-1, 1]. */
    static double noise(long seed, double x) {
        double floor = Math.floor(x);
        long i = (long) floor;
        double t = x - floor;
        double smooth = t * t * (3 - 2 * t);
        double a = hashToUnit(seed, i) * 2 - 1;
        double b = hashToUnit(seed, i + 1) * 2 - 1;
        return a + (b - a) * smooth;
    }

    private static double hashToUnit(long seed, long index) {
        long z = mix64(seed + index * 0x9E3779B97F4A7C15L);
        return (z >>> 11) * 0x1.0p-53;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}

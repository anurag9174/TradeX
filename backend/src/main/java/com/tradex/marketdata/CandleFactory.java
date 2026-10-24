package com.tradex.marketdata;

import com.tradex.entity.Instrument;
import com.tradex.enums.Timeframe;
import com.tradex.trading.Decimals;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the {@link SimulatedPriceModel} into OHLCV bars.
 *
 * <p>Every timeframe samples the same continuous price function on a sub-grid whose step is capped
 * so that even a weekly candle costs at most ~290 evaluations. Because the close of a bar is always
 * {@code price(openTime + period)}, the closes of a 1-minute series and a 1-day series agree, and
 * indicators computed on any timeframe are built from genuinely consistent data.</p>
 */
@Component
public class CandleFactory {

    /** Upper bound on sub-samples per bar; keeps weekly generation cheap. */
    private static final int MAX_SUB_STEPS = 288;

    private final SimulatedPriceModel priceModel;

    public CandleFactory(SimulatedPriceModel priceModel) {
        this.priceModel = priceModel;
    }

    /** Floors an instant to the start of its timeframe bucket, in UTC. */
    public Instant alignOpenTime(Instant time, Timeframe timeframe) {
        long seconds = time.getEpochSecond();
        long period = timeframe.seconds();
        long aligned = Math.floorDiv(seconds, period) * period;
        return Instant.ofEpochSecond(aligned);
    }

    /** The bar that fully covers {@code [openTime, openTime + period)}. */
    public CandleBar closedBar(Instrument instrument, Timeframe timeframe, Instant openTime) {
        Instant end = openTime.plusSeconds(timeframe.seconds());
        List<BigDecimal> samples = sample(instrument, openTime, end, timeframe);
        return bar(instrument, timeframe, openTime, samples, 1.0);
    }

    /**
     * The bar currently forming. {@code now} must lie inside the bucket; samples are taken only up
     * to {@code now} and volume is pro-rated by the elapsed fraction.
     */
    public CandleBar liveBar(Instrument instrument, Timeframe timeframe, Instant openTime, Instant now) {
        Instant end = openTime.plusSeconds(timeframe.seconds());
        Instant clampedNow = now.isBefore(end) ? now : end;
        if (clampedNow.isBefore(openTime)) {
            clampedNow = openTime;
        }
        List<BigDecimal> samples = sample(instrument, openTime, clampedNow, timeframe);
        double elapsed = (double) (clampedNow.getEpochSecond() - openTime.getEpochSecond())
                / Math.max(1, timeframe.seconds());
        return bar(instrument, timeframe, openTime, samples, Math.max(0.01, Math.min(1.0, elapsed)));
    }

    /**
     * Rolling window of bars ending with the one currently forming, ascending by open time.
     * Used when the database has no cached history for an instrument/timeframe.
     */
    public List<CandleBar> generatedSeries(Instrument instrument, Timeframe timeframe, int limit, Instant now) {
        Instant currentOpen = alignOpenTime(now, timeframe);
        List<CandleBar> bars = new ArrayList<>(limit);
        Instant start = currentOpen.minusSeconds((long) timeframe.seconds() * (limit - 1));
        for (int i = 0; i < limit - 1; i++) {
            Instant openTime = start.plusSeconds((long) timeframe.seconds() * i);
            bars.add(closedBar(instrument, timeframe, openTime));
        }
        bars.add(liveBar(instrument, timeframe, currentOpen, now));
        return bars;
    }

    /** Bar covering a fixed period, e.g. the whole of a given UTC day. */
    public CandleBar barForPeriod(Instrument instrument, Instant openTime, Timeframe period) {
        return closedBar(instrument, period, alignOpenTime(openTime, period));
    }

    // ---------------------------------------------------------------------------------------

    private List<BigDecimal> sample(Instrument instrument, Instant from, Instant to, Timeframe timeframe) {
        long period = timeframe.seconds();
        int subSteps = (int) Math.max(1, Math.min(MAX_SUB_STEPS, period / 60));
        long subStep = Math.max(60, period / subSteps);
        List<BigDecimal> samples = new ArrayList<>(subSteps + 1);
        long elapsed = to.getEpochSecond() - from.getEpochSecond();
        long steps = elapsed <= 0 ? 0 : Math.min(subSteps, Math.max(1, elapsed / subStep));
        for (long i = 0; i <= steps; i++) {
            Instant t = from.plusSeconds(i * subStep);
            if (t.isAfter(to)) {
                t = to;
            }
            samples.add(priceModel.tickAlignedPrice(instrument, t));
        }
        if (samples.isEmpty()) {
            samples.add(priceModel.tickAlignedPrice(instrument, from));
        }
        return samples;
    }

    private CandleBar bar(Instrument instrument,
                          Timeframe timeframe,
                          Instant openTime,
                          List<BigDecimal> samples,
                          double volumeFraction) {
        BigDecimal open = samples.get(0);
        BigDecimal close = samples.get(samples.size() - 1);
        BigDecimal high = open;
        BigDecimal low = open;
        for (BigDecimal s : samples) {
            if (s.compareTo(high) > 0) {
                high = s;
            }
            if (s.compareTo(low) < 0) {
                low = s;
            }
        }
        BigDecimal volume = priceModel.volumeFor(instrument, openTime, timeframe.seconds())
                .multiply(BigDecimal.valueOf(volumeFraction), Decimals.MC)
                .setScale(Decimals.MONEY_SCALE, RoundingMode.HALF_UP);
        return new CandleBar(openTime.truncatedTo(ChronoUnit.SECONDS), open, high, low, close, volume);
    }
}

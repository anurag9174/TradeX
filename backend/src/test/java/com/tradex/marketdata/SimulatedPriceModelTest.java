package com.tradex.marketdata;

import com.tradex.entity.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The simulated feed is a pure function of (instrument, absolute time). These tests pin that
 * contract down, because the entire trading engine - fills, candles, P&amp;L - builds on it.
 */
class SimulatedPriceModelTest {

    private static final Instant T0 = Instant.parse("2026-03-10T09:15:00Z");
    private static final Instant T1 = Instant.parse("2026-03-10T09:16:00Z");

    private final SimulatedPriceModel model = new SimulatedPriceModel();

    private static Instrument equity() {
        Instrument instrument = new Instrument();
        instrument.setId(1L);
        instrument.setSymbol("RELIANCE");
        instrument.setSeedPrice(new BigDecimal("1290.0000"));
        instrument.setVolatility(new BigDecimal("0.015000"));
        instrument.setTickSize(new BigDecimal("0.05"));
        return instrument;
    }

    @Test
    void priceIsDeterministicForTheSameInstrumentAndInstant() {
        BigDecimal first = model.priceAt(equity(), T0);
        BigDecimal second = model.priceAt(equity(), T0);
        assertThat(second).isEqualByComparingTo(first);

        // An entirely separate model instance must agree: nothing depends on JVM start-up time.
        SimulatedPriceModel fresh = new SimulatedPriceModel();
        assertThat(fresh.priceAt(equity(), T0)).isEqualByComparingTo(first);
    }

    @Test
    void differentInstantsProduceDifferentPrices() {
        assertThat(model.priceAt(equity(), T1)).isNotEqualByComparingTo(model.priceAt(equity(), T0));
    }

    @Test
    void twoInstrumentsNeverMoveInLockstep() {
        Instrument other = equity();
        other.setId(2L);
        other.setSymbol("TCS");
        other.setSeedPrice(new BigDecimal("4120.0000"));

        // A shared time axis with per-instrument seeds must decorrelate the series.
        assertThat(model.priceAt(equity(), T0)).isNotEqualByComparingTo(model.priceAt(other, T0));
    }

    @Test
    void tickAlignedPricesSnapToTheInstrumentGrid() {
        for (Instant when : new Instant[]{T0, T0.plusSeconds(7), T0.plusSeconds(1234), T1}) {
            BigDecimal snapped = model.tickAlignedPrice(equity(), when);
            BigDecimal remainder = snapped.remainder(new BigDecimal("0.05"));
            assertThat(remainder).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    void simulatedPricesStayPositiveAndBoundedAroundTheSeed() {
        // Sanity band: the model is unbiased noise around the seed, not a random walk to zero.
        for (int minute = 0; minute < 500; minute += 37) {
            BigDecimal price = model.priceAt(equity(), T0.plusSeconds(60L * minute));
            assertThat(price).isGreaterThan(new BigDecimal("1290").multiply(new BigDecimal("0.30")));
            assertThat(price).isLessThan(new BigDecimal("1290").multiply(new BigDecimal("3.00")));
        }
    }

    @Test
    void volumeIsDeterministicAndPositive() {
        BigDecimal first = model.volumeFor(equity(), T0, 60);
        BigDecimal second = model.volumeFor(equity(), T0, 60);
        assertThat(second).isEqualByComparingTo(first);
        assertThat(first).isGreaterThan(BigDecimal.ZERO);

        // A different candle window must draw fresh volume.
        assertThat(model.volumeFor(equity(), T1, 60)).isNotEqualByComparingTo(first);
    }
}

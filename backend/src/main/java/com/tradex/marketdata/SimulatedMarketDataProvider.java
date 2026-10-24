package com.tradex.marketdata;

import com.tradex.entity.Instrument;
import com.tradex.enums.MarketDataSource;
import com.tradex.enums.Timeframe;
import com.tradex.trading.Decimals;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generates a self-consistent synthetic market from {@link SimulatedPriceModel}.
 *
 * <p>This provider is always available and always deterministic. Everything it returns is tagged
 * {@link MarketDataSource#SIMULATED} so no caller can mistake it for a real quote.</p>
 */
@Component
public class SimulatedMarketDataProvider implements MarketDataProvider {

    public static final String PROVIDER_ID = "SIMULATED";

    private final SimulatedPriceModel priceModel;
    private final CandleFactory candleFactory;

    public SimulatedMarketDataProvider(SimulatedPriceModel priceModel, CandleFactory candleFactory) {
        this.priceModel = priceModel;
        this.candleFactory = candleFactory;
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return "TradeX Simulated Feed";
    }

    @Override
    public boolean live() {
        return false;
    }

    @Override
    public MarketDataSource source() {
        return MarketDataSource.SIMULATED;
    }

    @Override
    public boolean supports(Instrument instrument) {
        return instrument != null
                && instrument.getSeedPrice() != null
                && instrument.getSeedPrice().signum() > 0;
    }

    @Override
    public String unsupportedReason(Instrument instrument) {
        return supports(instrument)
                ? null
                : "Instrument has no anchor price configured for the simulated feed.";
    }

    @Override
    public Optional<QuoteSnapshot> quote(Instrument instrument) {
        return supports(instrument) ? Optional.of(buildQuote(instrument, Instant.now())) : Optional.empty();
    }

    @Override
    public Map<Long, QuoteSnapshot> quotes(List<Instrument> instruments) {
        Instant now = Instant.now();
        Map<Long, QuoteSnapshot> result = new HashMap<>(instruments.size() * 2);
        for (Instrument instrument : instruments) {
            if (supports(instrument)) {
                result.put(instrument.getId(), buildQuote(instrument, now));
            }
        }
        return result;
    }

    @Override
    public List<CandleBar> candles(Instrument instrument, Timeframe timeframe, int limit) {
        if (!supports(instrument) || limit <= 0) {
            return List.of();
        }
        return candleFactory.generatedSeries(instrument, timeframe, limit, Instant.now());
    }

    @Override
    public ProviderStatus status() {
        return new ProviderStatus(id(), false, ProviderHealth.OK,
                "Deterministic simulated feed active. Prices are generated, not observed.",
                0L, null);
    }

    // ---------------------------------------------------------------------------------------

    /**
     * Builds a full quote: previous close, session open/high/low and session volume for the current
     * UTC day, all derived from the same price function the chart uses.
     */
    public QuoteSnapshot buildQuote(Instrument instrument, Instant now) {
        Instant dayStart = candleFactory.alignOpenTime(now, Timeframe.D1);
        Instant previousDayStart = dayStart.minus(Duration.ofDays(1));

        BigDecimal previousClose = priceModel.tickAlignedPrice(instrument, previousDayStart);
        CandleBar today = candleFactory.liveBar(instrument, Timeframe.D1, dayStart, now);
        BigDecimal last = priceModel.tickAlignedPrice(instrument, now);

        // Keep the live bar honest: the instant price is the last traded price.
        BigDecimal high = Decimals.max(today.high(), last);
        BigDecimal low = Decimals.min(today.low(), last);

        BigDecimal spread = spreadFor(instrument);
        BigDecimal bid = Decimals.toTick(last.subtract(spread.divide(BigDecimal.TWO, Decimals.MC)),
                instrument.getTickSize());
        BigDecimal ask = Decimals.toTick(last.add(spread.divide(BigDecimal.TWO, Decimals.MC)),
                instrument.getTickSize());

        return new QuoteSnapshot(
                instrument.getSymbol(),
                last,
                previousClose,
                today.open(),
                high,
                low,
                today.volume(),
                bid,
                ask,
                MarketDataSource.SIMULATED,
                now);
    }

    /** Deterministic spread proportional to the instrument's volatility, with a tick-size floor. */
    private BigDecimal spreadFor(Instrument instrument) {
        BigDecimal reference = instrument.getSeedPrice();
        BigDecimal proportional = reference
                .multiply(instrument.getVolatility(), Decimals.MC)
                .multiply(new BigDecimal("0.012"), Decimals.MC);
        BigDecimal minimum = instrument.getTickSize().multiply(BigDecimal.valueOf(2));
        BigDecimal spread = Decimals.max(proportional, minimum);
        return Decimals.money(spread);
    }

    /** Number of bars the generated series can produce for a timeframe (used by the bootstrapper). */
    public List<CandleBar> history(Instrument instrument, Timeframe timeframe, int limit, Instant now) {
        List<CandleBar> bars = new ArrayList<>(limit);
        Instant currentOpen = candleFactory.alignOpenTime(now, timeframe);
        Instant start = currentOpen.minusSeconds((long) timeframe.seconds() * limit);
        for (int i = 0; i < limit; i++) {
            bars.add(candleFactory.closedBar(instrument, timeframe, start.plusSeconds((long) timeframe.seconds() * i)));
        }
        return bars;
    }
}

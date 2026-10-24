package com.tradex.marketdata;

import com.tradex.entity.Instrument;
import com.tradex.enums.MarketDataSource;
import com.tradex.enums.Timeframe;
import com.tradex.repository.MarketCandleRepository;
import com.tradex.trading.Decimals;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves candle series to the chart.
 *
 * <h2>Live mode</h2>
 * A live series never contains an invented bar. It is exactly what the provider returned: closed
 * bars plus the exchange's own still-forming bar. Closed bars are stored ({@code source=LIVE}) on a
 * best-effort basis so a provider outage can fall back to the last <em>real</em> bars. When nothing
 * can be shown, the series comes back empty with an explicit note; generated candles are never
 * mixed into a real chart.
 *
 * <h2>Simulated mode</h2>
 * <ol>
 *   <li>Closed bars come from {@code market_candles} rows with {@code source=SIMULATED}.</li>
 *   <li>If that history is short - a freshly added instrument, or a database that was never
 *       bootstrapped - the bars are synthesised on the fly from the deterministic price model so
 *       the simulated chart is never empty. They are not written back; bootstrapping owns
 *       persistence.</li>
 *   <li>The bar currently forming is computed live from the same price model, so the last candle
 *       ticks as the simulated tape moves.</li>
 * </ol>
 *
 * <p>Closed bars are immutable per bucket, so the simulated cache keyed by
 * {@code instrument:timeframe:bucketStart} never needs invalidation - a new bucket simply produces a
 * new key and the old entry is evicted by the size cap.</p>
 */
@Service
public class CandleService {

    private static final int MAX_CACHE_ENTRIES = 512;

    private final MarketCandleRepository candleRepository;
    private final MarketDataRegistry registry;
    private final CandleFactory candleFactory;
    private final LiveCandleStore liveCandleStore;

    private final Map<String, List<CandleBar>> closedCache = Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<CandleBar>> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });

    public CandleService(MarketCandleRepository candleRepository,
                         MarketDataRegistry registry,
                         CandleFactory candleFactory,
                         LiveCandleStore liveCandleStore) {
        this.candleRepository = candleRepository;
        this.registry = registry;
        this.candleFactory = candleFactory;
        this.liveCandleStore = liveCandleStore;
    }

    /** Result wrapper so callers can label the chart honestly. */
    public record Series(List<CandleBar> bars,
                         MarketDataSource source,
                         boolean live,
                         boolean synthesised,
                         String note) {
    }

    @Transactional(readOnly = true)
    public Series series(Instrument instrument, Timeframe timeframe, int limit) {
        int safeLimit = Math.min(Math.max(limit, 10), 2000);
        Instant now = Instant.now();
        Instant currentBucket = candleFactory.alignOpenTime(now, timeframe);

        MarketDataProvider provider = registry.providerFor(instrument);
        if (!provider.supports(instrument)) {
            return new Series(List.of(), provider.source(), provider.live(), false,
                    registry.unsupportedReason(instrument));
        }
        return provider.live()
                ? liveSeries(instrument, timeframe, safeLimit, currentBucket, provider)
                : simulatedSeries(instrument, timeframe, safeLimit, currentBucket);
    }

    // ---------------------------------------------------------------------------------------
    // Live path
    // ---------------------------------------------------------------------------------------

    private Series liveSeries(Instrument instrument, Timeframe timeframe, int safeLimit,
                              Instant currentBucket, MarketDataProvider provider) {
        // One extra bar: exchanges (Delta included) return the bar that is still forming as their
        // newest element, and it is genuine data - the exchange's own partial bar.
        List<CandleBar> fetched = provider.candles(instrument, timeframe, safeLimit + 1);
        if (!fetched.isEmpty()) {
            CandleBar forming = null;
            List<CandleBar> closed = new ArrayList<>(fetched.size());
            for (CandleBar bar : fetched) {
                if (bar.openTime().isBefore(currentBucket)) {
                    closed.add(bar);
                } else if (forming == null || bar.openTime().isAfter(forming.openTime())) {
                    forming = bar;
                }
            }
            closed = trimTail(closed, Math.max(safeLimit - 1, 1));
            liveCandleStore.store(instrument, timeframe, closed);
            List<CandleBar> bars = new ArrayList<>(closed.size() + 1);
            bars.addAll(closed);
            if (forming != null) {
                bars.add(forming);
            }
            return new Series(bars, MarketDataSource.LIVE, true, false,
                    "Live candles from " + provider.displayName() + " - real market data, paper fills.");
        }
        // The provider answered with nothing. Fall back to the live bars stored earlier - never
        // generated ones: a synthesised bar inside a real chart would be a lie about the market.
        List<CandleBar> stored = candleRepository
                .findRecentBySourceDesc(instrument.getId(), timeframe, MarketDataSource.LIVE,
                        Limit.of(Math.max(safeLimit - 1, 1)))
                .stream()
                .map(candle -> new CandleBar(candle.getOpenTime(), candle.getOpen(), candle.getHigh(),
                        candle.getLow(), candle.getClose(), candle.getVolume()))
                .sorted(Comparator.comparing(CandleBar::openTime))
                .toList();
        String note = stored.isEmpty()
                ? "No live candles available: " + provider.displayName() + " returned nothing and no live "
                        + "bars have been stored yet. Nothing is generated while live mode is active."
                : "Live provider returned no candles right now; showing the last stored live bars. "
                        + "They are real but not refreshing.";
        return new Series(stored, MarketDataSource.LIVE, true, false, note);
    }

    // ---------------------------------------------------------------------------------------
    // Simulated path
    // ---------------------------------------------------------------------------------------

    private Series simulatedSeries(Instrument instrument, Timeframe timeframe, int safeLimit,
                                   Instant currentBucket) {
        Loaded loaded = loadSimulated(instrument, timeframe, safeLimit - 1, currentBucket);
        List<CandleBar> bars = new ArrayList<>(loaded.bars().size() + 1);
        bars.addAll(loaded.bars());
        bars.add(candleFactory.liveBar(instrument, timeframe, currentBucket, Instant.now()));
        return new Series(bars, MarketDataSource.SIMULATED, false, loaded.synthesised(),
                "Simulated candles - generated by the TradeX price model, not observed market data");
    }

    /** Closed simulated bars only, ascending, ending strictly before {@code before}. */
    @Transactional(readOnly = true)
    public List<CandleBar> closedBars(Instrument instrument, Timeframe timeframe, int limit, Instant before) {
        return loadSimulated(instrument, timeframe, limit, before).bars();
    }

    private record Loaded(List<CandleBar> bars, boolean synthesised) {
    }

    private Loaded loadSimulated(Instrument instrument, Timeframe timeframe, int limit, Instant before) {
        if (limit <= 0) {
            return new Loaded(List.of(), false);
        }
        Instant currentBucket = candleFactory.alignOpenTime(before, timeframe);
        String key = instrument.getId() + ":" + timeframe.name() + ":" + currentBucket.getEpochSecond();
        List<CandleBar> cached = closedCache.get(key);
        if (cached != null && cached.size() >= limit) {
            // The bucket is immutable, so a longer cached series simply answers a shorter request.
            return new Loaded(List.copyOf(cached.subList(cached.size() - limit, cached.size())), false);
        }
        Instant to = currentBucket.minusSeconds(1);
        Instant from = currentBucket.minusSeconds((long) timeframe.seconds() * limit);
        List<CandleBar> bars = candleRepository
                .findByInstrumentIdAndTimeframeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                        instrument.getId(), timeframe, MarketDataSource.SIMULATED, from, to)
                .stream()
                .map(candle -> new CandleBar(candle.getOpenTime(), candle.getOpen(), candle.getHigh(),
                        candle.getLow(), candle.getClose(), candle.getVolume()))
                .toList();

        boolean synthesised = false;
        if (bars.size() < limit) {
            bars = synthesise(instrument, timeframe, limit, currentBucket, bars);
            synthesised = true;
        }
        List<CandleBar> trimmed = trimTail(bars, limit);
        // Grow-only: a later, longer request upgrades the entry; shorter ones are served by slicing.
        if (cached == null || trimmed.size() > cached.size()) {
            closedCache.put(key, trimmed);
        }
        return new Loaded(trimmed, synthesised);
    }

    private static List<CandleBar> trimTail(List<CandleBar> bars, int limit) {
        if (bars.size() <= limit) {
            return List.copyOf(bars);
        }
        return List.copyOf(bars.subList(bars.size() - limit, bars.size()));
    }

    /** Fills a short or empty history using the same deterministic model the bootstrapper uses. */
    private List<CandleBar> synthesise(Instrument instrument, Timeframe timeframe, int limit,
                                       Instant currentBucket, List<CandleBar> existing) {
        Instant start = currentBucket.minusSeconds((long) timeframe.seconds() * limit);
        List<CandleBar> generated = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            generated.add(candleFactory.closedBar(instrument, timeframe, start.plusSeconds((long) timeframe.seconds() * i)));
        }
        if (existing.isEmpty()) {
            return generated;
        }
        // Overlay whichever bars were already persisted so stored history wins over regenerated data.
        Map<Instant, CandleBar> byTime = new HashMap<>();
        for (CandleBar bar : generated) {
            byTime.put(bar.openTime(), bar);
        }
        for (CandleBar bar : existing) {
            byTime.put(bar.openTime(), bar);
        }
        List<Instant> times = new ArrayList<>(byTime.keySet());
        Collections.sort(times);
        List<CandleBar> merged = new ArrayList<>(times.size());
        for (Instant time : times) {
            merged.add(byTime.get(time));
        }
        return merged;
    }

    /** Latest close available for an instrument on a timeframe, falling back to the live bar. */
    @Transactional(readOnly = true)
    public BigDecimal latestClose(Instrument instrument, Timeframe timeframe) {
        Series series = series(instrument, timeframe, 10);
        if (series.bars().isEmpty()) {
            return null;
        }
        return Decimals.money(series.bars().get(series.bars().size() - 1).close());
    }

    /** Drops every cached series. Used by the reset / maintenance paths. */
    public void invalidateAll() {
        closedCache.clear();
    }

    public void invalidate(Long instrumentId) {
        String prefix = instrumentId + ":";
        synchronized (closedCache) {
            closedCache.keySet().removeIf(key -> key.startsWith(prefix));
        }
    }
}

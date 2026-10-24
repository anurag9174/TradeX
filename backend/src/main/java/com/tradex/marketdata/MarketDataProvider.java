package com.tradex.marketdata;

import com.tradex.entity.Instrument;
import com.tradex.enums.MarketDataSource;
import com.tradex.enums.Timeframe;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Contract every market-data source implements.
 *
 * <p>Implementations must be honest about what they can serve: if an instrument cannot be resolved
 * on a provider, {@link #supports(Instrument)} returns {@code false} and
 * {@link #unsupportedReason(Instrument)} explains why. Callers then surface an explicit
 * "unavailable" state instead of inventing a price.</p>
 */
public interface MarketDataProvider {

    /** Stable provider id, e.g. {@code SIMULATED} or {@code TWELVEDATA}. */
    String id();

    /** Human readable provider name shown in the UI data-source badge. */
    String displayName();

    /** {@code true} only for providers that stream real market data. */
    boolean live();

    MarketDataSource source();

    /**
     * {@code true} when the provider has everything it needs to serve live data at all.
     *
     * <p>Key-based providers report whether a key is present; providers on a public, key-less API
     * (Delta Exchange market data) are always configured. The registry uses this to decide whether
     * {@code mode=live} can start and whether {@code mode=auto} goes live.</p>
     */
    default boolean isConfigured() {
        return true;
    }

    boolean supports(Instrument instrument);

    /** Human readable explanation used when {@link #supports(Instrument)} is false. */
    String unsupportedReason(Instrument instrument);

    /** Latest quote, or empty when the provider has nothing for this instrument right now. */
    Optional<QuoteSnapshot> quote(Instrument instrument);

    /** Batched quotes. Implementations should batch the upstream call where the API allows it. */
    Map<Long, QuoteSnapshot> quotes(List<Instrument> instruments);

    /** Most recent {@code limit} candles, ascending by {@code openTime}. */
    List<CandleBar> candles(Instrument instrument, Timeframe timeframe, int limit);

    /** Diagnostics for the health endpoint. */
    ProviderStatus status();

    record ProviderStatus(String provider,
                          boolean live,
                          ProviderHealth health,
                          String message,
                          long latencyMillis,
                          String lastErrorAt) {
    }

    enum ProviderHealth {
        OK,
        DEGRADED,
        UNCONFIGURED,
        ERROR
    }
}

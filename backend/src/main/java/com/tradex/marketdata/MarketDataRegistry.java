package com.tradex.marketdata;

import com.tradex.config.MarketDataProperties;
import com.tradex.entity.Instrument;
import com.tradex.enums.MarketDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Decides which {@link MarketDataProvider} serves the platform.
 *
 * <h2>Mode resolution</h2>
 * <ul>
 *   <li>{@code tradex.marketdata.mode=simulated} - always the generated feed.</li>
 *   <li>{@code tradex.marketdata.mode=live} - require the selected live provider to be configured;
 *       fail fast at startup rather than silently degrading.</li>
 *   <li>{@code tradex.marketdata.mode=auto} (default) - live when the selected provider is
 *       configured, otherwise the clearly-labelled simulated feed.</li>
 * </ul>
 *
 * <h2>Provider selection</h2>
 * {@code tradex.marketdata.provider} picks the live provider:
 * <ul>
 *   <li>{@code twelvedata} (default) - equities and crypto via Twelve Data; requires an API key, so
 *       {@code auto} stays simulated until a key is present.</li>
 *   <li>{@code delta} - Delta Exchange public market data; needs <em>no</em> API key, so
 *       {@code auto} (or {@code live}) goes live as soon as the provider is explicitly selected.
 *       Delta lists crypto derivatives only; unmapped instruments report "live price unavailable".</li>
 * </ul>
 *
 * <p>Once a live provider is selected it stays selected for the whole JVM run. There is no automatic
 * fallback to simulated prices, because doing so would present fabricated numbers as market data.</p>
 */
@Component
public class MarketDataRegistry {

    public static final String PROVIDER_TWELVEDATA = "twelvedata";
    public static final String PROVIDER_DELTA = "delta";

    private static final Logger log = LoggerFactory.getLogger(MarketDataRegistry.class);

    private final MarketDataProperties properties;
    private final SimulatedMarketDataProvider simulatedProvider;
    private final TwelveDataMarketDataProvider twelveDataProvider;
    private final DeltaExchangeMarketDataProvider deltaProvider;

    private final AtomicReference<MarketDataProvider> selectedProvider = new AtomicReference<>();
    private final AtomicBoolean liveActive = new AtomicBoolean(false);
    private final AtomicBoolean initialised = new AtomicBoolean(false);

    public MarketDataRegistry(MarketDataProperties properties,
                              SimulatedMarketDataProvider simulatedProvider,
                              TwelveDataMarketDataProvider twelveDataProvider,
                              DeltaExchangeMarketDataProvider deltaProvider) {
        this.properties = properties;
        this.simulatedProvider = simulatedProvider;
        this.twelveDataProvider = twelveDataProvider;
        this.deltaProvider = deltaProvider;
    }

    /** Called once after startup so misconfiguration is reported loudly. */
    public void initialise() {
        if (!initialised.compareAndSet(false, true)) {
            return;
        }
        MarketDataProvider provider = selectProvider();
        selectedProvider.set(provider);
        String mode = properties.getMode() == null ? "auto" : properties.getMode().trim().toLowerCase();
        boolean configured = provider.isConfigured();
        switch (mode) {
            case "live" -> {
                if (!configured) {
                    throw new IllegalStateException(
                            "tradex.marketdata.mode=live but provider '" + provider.id() + "' is not configured. "
                                    + "Set TRADEX_MARKETDATA_API_KEY (or tradex.marketdata.api-key) for Twelve Data, "
                                    + "or select the key-less Delta provider with TRADEX_MARKETDATA_PROVIDER=delta. "
                                    + "Refusing to start: a live session must never silently serve simulated prices.");
                }
                liveActive.set(true);
                log.info("Market data: LIVE via {}", provider.displayName());
            }
            case "simulated" -> {
                liveActive.set(false);
                log.info("Market data: SIMULATED (forced by configuration). Prices are generated, not observed.");
            }
            default -> {
                if (configured) {
                    liveActive.set(true);
                    log.info("Market data: LIVE via {} (auto-detected).", provider.displayName());
                } else {
                    liveActive.set(false);
                    log.info("Market data: SIMULATED. Provider '{}' is not configured. "
                            + "Prices are generated for practice and are NOT real market prices.", provider.id());
                }
            }
        }
    }

    private MarketDataProvider selectProvider() {
        String raw = properties.getProvider();
        String id = raw == null || raw.isBlank() ? PROVIDER_TWELVEDATA
                : raw.trim().toLowerCase(Locale.ROOT);
        return switch (id) {
            case PROVIDER_TWELVEDATA, "twelve_data", "twelve-data" -> twelveDataProvider;
            case PROVIDER_DELTA, "deltaexchange", "delta-exchange" -> deltaProvider;
            default -> throw new IllegalStateException(
                    "Unknown tradex.marketdata.provider '" + raw + "'. Supported: "
                            + PROVIDER_TWELVEDATA + ", " + PROVIDER_DELTA + ".");
        };
    }

    public boolean isLiveMode() {
        if (!initialised.get()) {
            initialise();
        }
        return liveActive.get();
    }

    public MarketDataSource activeSource() {
        return isLiveMode() ? MarketDataSource.LIVE : MarketDataSource.SIMULATED;
    }

    public String activeLabel() {
        return isLiveMode()
                ? "LIVE - " + live().displayName()
                : "SIMULATED DATA - generated prices, not a real market feed";
    }

    public String activeProviderId() {
        return isLiveMode() ? live().id() : simulatedProvider.id();
    }

    /** The provider that will actually answer for this instrument, accounting for coverage gaps. */
    public MarketDataProvider providerFor(Instrument instrument) {
        if (isLiveMode()) {
            // Whether it supports this instrument or not, the live provider owns the answer -
            // unsupported instruments surface an explicit "unavailable" state instead of a fake.
            return live();
        }
        return simulatedProvider;
    }

    public boolean isSupported(Instrument instrument) {
        return providerFor(instrument).supports(instrument);
    }

    public String unsupportedReason(Instrument instrument) {
        MarketDataProvider provider = providerFor(instrument);
        return provider.supports(instrument) ? null : provider.unsupportedReason(instrument);
    }

    public SimulatedMarketDataProvider simulated() {
        return simulatedProvider;
    }

    /** The live provider selected by {@code tradex.marketdata.provider}. */
    public MarketDataProvider live() {
        if (!initialised.get()) {
            initialise();
        }
        return selectedProvider.get();
    }

    /** True when the selected live provider has everything it needs (e.g. an API key). */
    public boolean liveConfigured() {
        return live().isConfigured();
    }

    /** Latency of the last live provider call, used to size the quote cache TTL. */
    public long liveLatencyMillis() {
        return live().status().latencyMillis();
    }

    /** Tick interval for the simulated feed. */
    public long simulatedTickMillis() {
        return Math.max(200L, properties.getSimulatedTickMillis());
    }

    public Map<String, Object> diagnostics() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", properties.getMode());
        map.put("provider", properties.getProvider());
        map.put("liveActive", isLiveMode());
        map.put("activeProvider", activeProviderId());
        map.put("label", activeLabel());
        map.put("simulatedStatus", simulatedProvider.status());
        map.put("liveStatus", live().status());
        map.put("twelvedataStatus", twelveDataProvider.status());
        map.put("deltaStatus", deltaProvider.status());
        return map;
    }
}

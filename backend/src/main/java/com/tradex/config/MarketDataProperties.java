package com.tradex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/** Market-data wiring: which provider to use and how often to poll it. */
@ConfigurationProperties(prefix = "tradex.marketdata")
public class MarketDataProperties {

    /**
     * {@code auto} (use a live provider when an API key is present, otherwise simulated),
     * {@code simulated} (force the generated feed) or {@code live} (require a live provider).
     */
    private String mode = "auto";

    /** Provider id to use in live mode: {@code twelvedata}. */
    private String provider = "twelvedata";

    /** API key for the live provider. Never commit a real key. */
    private String apiKey;

    /** Base URL of the live provider. */
    private String baseUrl = "https://api.twelvedata.com";

    /**
     * Base URL of Delta Exchange's public market data API. No API key is required for it; keys are
     * only needed once real order execution is wired up. Use
     * {@code https://api.india.delta.exchange} for Delta India accounts and
     * {@code https://cdn-ind.testnet.deltaex.org} for the demo (testnet) environment.
     */
    private String deltaBaseUrl = "https://api.india.delta.exchange";

    /** Seconds between live quote polls. Irrelevant in simulated mode. */
    private int quotePollSeconds = 15;

    /** Seconds between live candle refreshes. */
    private int candleRefreshSeconds = 300;

    /** Milliseconds between simulated ticks. */
    private int simulatedTickMillis = 1000;

    /** How many days of history to synthesise the first time the app boots. */
    private int bootstrapDays = 400;

    /** Persist generated candles so restarts are instant. */
    private boolean persistCandles = true;

    /** HTTP timeout for provider calls. */
    private int httpTimeoutMillis = 8000;

    /** Provider-specific symbol overrides: canonical symbol -> provider symbol. */
    private Map<String, String> symbolOverrides = new HashMap<>();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getDeltaBaseUrl() {
        return deltaBaseUrl;
    }

    public void setDeltaBaseUrl(String deltaBaseUrl) {
        this.deltaBaseUrl = deltaBaseUrl;
    }

    public int getQuotePollSeconds() {
        return quotePollSeconds;
    }

    public void setQuotePollSeconds(int quotePollSeconds) {
        this.quotePollSeconds = quotePollSeconds;
    }

    public int getCandleRefreshSeconds() {
        return candleRefreshSeconds;
    }

    public void setCandleRefreshSeconds(int candleRefreshSeconds) {
        this.candleRefreshSeconds = candleRefreshSeconds;
    }

    public int getSimulatedTickMillis() {
        return simulatedTickMillis;
    }

    public void setSimulatedTickMillis(int simulatedTickMillis) {
        this.simulatedTickMillis = simulatedTickMillis;
    }

    public int getBootstrapDays() {
        return bootstrapDays;
    }

    public void setBootstrapDays(int bootstrapDays) {
        this.bootstrapDays = bootstrapDays;
    }

    public boolean isPersistCandles() {
        return persistCandles;
    }

    public void setPersistCandles(boolean persistCandles) {
        this.persistCandles = persistCandles;
    }

    public int getHttpTimeoutMillis() {
        return httpTimeoutMillis;
    }

    public void setHttpTimeoutMillis(int httpTimeoutMillis) {
        this.httpTimeoutMillis = httpTimeoutMillis;
    }

    public Map<String, String> getSymbolOverrides() {
        return symbolOverrides;
    }

    public void setSymbolOverrides(Map<String, String> symbolOverrides) {
        this.symbolOverrides = symbolOverrides == null ? new HashMap<>() : symbolOverrides;
    }
}

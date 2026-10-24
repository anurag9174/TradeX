package com.tradex.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradex.config.MarketDataProperties;
import com.tradex.entity.Instrument;
import com.tradex.enums.AssetClass;
import com.tradex.enums.MarketDataSource;
import com.tradex.enums.Timeframe;
import com.tradex.trading.Decimals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Live market data via <a href="https://twelvedata.com">Twelve Data</a>.
 *
 * <h2>Provider-specific symbol mapping</h2>
 * The platform stores a provider-neutral {@code VENUE:TICKER} reference on every instrument
 * ({@code NSE:RELIANCE}, {@code CRYPTO:BTC/INR}). This adapter translates it into what Twelve Data
 * actually expects:
 * <table>
 *   <tr><th>Canonical</th><th>Twelve Data request</th></tr>
 *   <tr><td>{@code NSE:RELIANCE}</td><td>{@code symbol=RELIANCE&exchange=NSE}</td></tr>
 *   <tr><td>{@code CRYPTO:BTC/INR}</td><td>{@code symbol=BTC/INR}</td></tr>
 * </table>
 *
 * <h2>Unsupported instruments</h2>
 * Twelve Data's coverage of Indian cash indices is plan-dependent and is therefore <b>not assumed</b>.
 * Index instruments are reported as unsupported unless the operator explicitly opts in through
 * {@code tradex.marketdata.symbol-overrides}. Unsupported instruments surface an explicit
 * "live price unavailable" state; a simulated price is never substituted while live mode is active.
 *
 * <h2>Failure behaviour</h2>
 * On a provider error the adapter returns no quote for the affected instruments and records the
 * failure. Callers label those instruments unavailable. Simulated prices are never mixed into a live
 * session.
 */
@Component
public class TwelveDataMarketDataProvider implements MarketDataProvider {

    public static final String PROVIDER_ID = "TWELVEDATA";

    private static final Logger log = LoggerFactory.getLogger(TwelveDataMarketDataProvider.class);

    private final MarketDataProperties properties;
    private final RestClient client;

    private volatile ProviderStatus lastStatus = new ProviderStatus(
            PROVIDER_ID, true, ProviderHealth.UNCONFIGURED, "No API key configured.", 0L, null);

    public TwelveDataMarketDataProvider(MarketDataProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(properties.getHttpTimeoutMillis());
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return "Twelve Data (live)";
    }

    @Override
    public boolean live() {
        return true;
    }

    @Override
    public MarketDataSource source() {
        return MarketDataSource.LIVE;
    }

    @Override
    public boolean isConfigured() {
        return properties.getApiKey() != null && !properties.getApiKey().isBlank();
    }

    @Override
    public boolean supports(Instrument instrument) {
        if (!isConfigured() || instrument == null) {
            return false;
        }
        ProviderSymbol mapping = resolve(instrument);
        if (mapping == null) {
            return false;
        }
        // Indices are opt-in only: coverage is plan dependent, so it is never assumed.
        return instrument.getAssetClass() != AssetClass.INDEX || mapping.explicitOverride();
    }

    @Override
    public String unsupportedReason(Instrument instrument) {
        if (!isConfigured()) {
            return "Live provider " + PROVIDER_ID + " is not configured (missing API key).";
        }
        ProviderSymbol mapping = resolve(instrument);
        if (mapping == null) {
            return "No Twelve Data symbol mapping for " + instrument.getSymbol()
                    + " (canonical " + instrument.getProviderSymbol() + ").";
        }
        if (instrument.getAssetClass() == AssetClass.INDEX && !mapping.explicitOverride()) {
            return "Twelve Data index coverage for " + instrument.getExchange()
                    + " is plan dependent and is not assumed by TradeX. Enable it with"
                    + " tradex.marketdata.symbol-overrides." + instrument.getSymbol() + ".";
        }
        return null;
    }

    @Override
    public Optional<QuoteSnapshot> quote(Instrument instrument) {
        Map<Long, QuoteSnapshot> result = quotes(List.of(instrument));
        return Optional.ofNullable(result.get(instrument.getId()));
    }

    @Override
    public Map<Long, QuoteSnapshot> quotes(List<Instrument> instruments) {
        List<Instrument> supported = instruments.stream().filter(this::supports).toList();
        if (supported.isEmpty()) {
            return Map.of();
        }
        long started = System.currentTimeMillis();
        Map<Long, QuoteSnapshot> result = new HashMap<>(supported.size() * 2);
        try {
            // Twelve Data accepts a comma separated symbol list; batching keeps us inside the
            // free-tier request budget.
            UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/quote")
                    .queryParam("symbol", supported.stream().map(this::requestSymbol).distinct().toList())
                    .queryParam("apikey", properties.getApiKey());
            String body = client.get().uri(builder.build(true).toUriString()).retrieve().body(String.class);
            JsonNode root = Json.read(body);
            if (root != null && root.has("status") && "error".equals(root.path("status").asText())) {
                String message = root.path("message").asText("unknown provider error");
                recordFailure(started, "quote error: " + message);
                return Map.of();
            }
            for (Instrument instrument : supported) {
                JsonNode node = root == null ? null : lookupQuoteNode(root, instrument);
                QuoteSnapshot snapshot = node == null ? null : toQuote(instrument, node);
                if (snapshot != null) {
                    result.put(instrument.getId(), snapshot);
                }
            }
            recordSuccess(started, result.size() + " quotes");
        } catch (Exception ex) {
            recordFailure(started, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            log.warn("Twelve Data quote fetch failed: {}", ex.getMessage());
        }
        return result;
    }

    @Override
    public List<CandleBar> candles(Instrument instrument, Timeframe timeframe, int limit) {
        if (!supports(instrument)) {
            return List.of();
        }
        long started = System.currentTimeMillis();
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/time_series")
                    .queryParam("symbol", requestSymbol(instrument))
                    .queryParam("interval", interval(timeframe))
                    .queryParam("outputsize", Math.min(Math.max(limit, 1), 5000))
                    .queryParam("order", "ASC")
                    .queryParam("timezone", "UTC")
                    .queryParam("apikey", properties.getApiKey());
            ProviderSymbol mapping = resolve(instrument);
            if (mapping != null && mapping.exchange() != null && instrument.getAssetClass() != AssetClass.CRYPTO) {
                builder.queryParam("exchange", mapping.exchange());
            }
            String body = client.get().uri(builder.build(true).toUriString()).retrieve().body(String.class);
            JsonNode root = Json.read(body);
            if (root == null) {
                recordFailure(started, "empty candle response");
                return List.of();
            }
            if (root.has("status") && "error".equals(root.path("status").asText())) {
                recordFailure(started, "candle error: " + root.path("message").asText("unknown"));
                return List.of();
            }
            JsonNode values = root.path("values");
            if (!values.isArray()) {
                recordFailure(started, "candle response has no values array");
                return List.of();
            }
            List<CandleBar> bars = new ArrayList<>(values.size());
            for (JsonNode node : values) {
                CandleBar bar = toBar(node, timeframe);
                if (bar != null) {
                    bars.add(bar);
                }
            }
            bars.sort((a, b) -> a.openTime().compareTo(b.openTime()));
            recordSuccess(started, bars.size() + " candles");
            return bars;
        } catch (Exception ex) {
            recordFailure(started, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            log.warn("Twelve Data candle fetch failed for {}: {}", instrument.getSymbol(), ex.getMessage());
            return List.of();
        }
    }

    @Override
    public ProviderStatus status() {
        if (!isConfigured()) {
            return new ProviderStatus(id(), true, ProviderHealth.UNCONFIGURED,
                    "No API key configured (TWELVEDATA_API_KEY).", 0L, null);
        }
        return lastStatus;
    }

    // ---------------------------------------------------------------------------------------
    // Symbol mapping
    // ---------------------------------------------------------------------------------------

    private record ProviderSymbol(String symbol, String exchange, boolean explicitOverride) {
    }

    /**
     * Translates the canonical {@code VENUE:TICKER} reference into Twelve Data's request format.
     * Returns {@code null} when this adapter cannot express the instrument at all.
     */
    ProviderSymbol resolve(Instrument instrument) {
        Map<String, String> overrides = properties.getSymbolOverrides();
        String override = overrides.get(instrument.getSymbol());
        if (override != null && !override.isBlank()) {
            return parseCanonical(override, true);
        }
        String canonical = instrument.getProviderSymbol();
        if (canonical == null || canonical.isBlank()) {
            return null;
        }
        ProviderSymbol parsed = parseCanonical(canonical, false);
        if (parsed == null) {
            return null;
        }
        if (instrument.getAssetClass() == AssetClass.CRYPTO && parsed.exchange() != null) {
            // Twelve Data addresses crypto pairs as a single symbol (BTC/INR); the venue prefix
            // must be dropped.
            return new ProviderSymbol(parsed.symbol(), null, false);
        }
        return parsed;
    }

    private ProviderSymbol parseCanonical(String canonical, boolean explicit) {
        int separator = canonical.indexOf(':');
        if (separator <= 0 || separator == canonical.length() - 1) {
            // Bare tickers are passed through untouched.
            return new ProviderSymbol(canonical.trim(), null, explicit);
        }
        String venue = canonical.substring(0, separator).trim();
        String ticker = canonical.substring(separator + 1).trim();
        if ("CRYPTO".equalsIgnoreCase(venue)) {
            return new ProviderSymbol(ticker, null, explicit);
        }
        // NSE / BSE / NASDAQ / NYSE ... all map to Twelve Data's `exchange` parameter.
        return new ProviderSymbol(ticker, venue.toUpperCase(), explicit);
    }

    private String requestSymbol(Instrument instrument) {
        ProviderSymbol mapping = resolve(instrument);
        if (mapping == null) {
            return instrument.getSymbol();
        }
        return mapping.exchange() == null ? mapping.symbol() : mapping.symbol();
    }

    String interval(Timeframe timeframe) {
        return switch (timeframe) {
            case M1 -> "1min";
            case M5 -> "5min";
            case M15 -> "15min";
            case M30 -> "30min";
            case H1 -> "1h";
            case H4 -> "4h";
            case D1 -> "1day";
            case W1 -> "1week";
        };
    }

    // ---------------------------------------------------------------------------------------
    // Response mapping
    // ---------------------------------------------------------------------------------------

    private JsonNode lookupQuoteNode(JsonNode root, Instrument instrument) {
        // A single-symbol request returns the quote at the root; a batch returns a map keyed by
        // the requested symbol.
        if (root.has("symbol")) {
            return root;
        }
        JsonNode direct = root.get(requestSymbol(instrument));
        if (direct != null) {
            return direct;
        }
        for (JsonNode child : root) {
            if (child.has("symbol") && instrument.getSymbol().equalsIgnoreCase(child.path("symbol").asText())) {
                return child;
            }
        }
        return null;
    }

    private QuoteSnapshot toQuote(Instrument instrument, JsonNode node) {
        BigDecimal last = decimal(node, "close");
        if (last == null) {
            last = decimal(node, "price");
        }
        if (last == null) {
            return null;
        }
        BigDecimal previousClose = decimal(node, "previous_close");
        BigDecimal open = decimal(node, "open");
        BigDecimal high = decimal(node, "high");
        BigDecimal low = decimal(node, "low");
        BigDecimal volume = decimal(node, "volume");
        Instant asOf = Instant.now();
        Long timestamp = node.has("timestamp") ? node.path("timestamp").asLong() : null;
        if (timestamp != null && timestamp > 0) {
            asOf = Instant.ofEpochSecond(timestamp);
        }
        return new QuoteSnapshot(
                instrument.getSymbol(),
                Decimals.toTick(last, instrument.getTickSize()),
                previousClose == null ? null : Decimals.toTick(previousClose, instrument.getTickSize()),
                open == null ? null : Decimals.toTick(open, instrument.getTickSize()),
                high == null ? null : Decimals.toTick(high, instrument.getTickSize()),
                low == null ? null : Decimals.toTick(low, instrument.getTickSize()),
                volume,
                null,
                null,
                MarketDataSource.LIVE,
                asOf);
    }

    private CandleBar toBar(JsonNode node, Timeframe timeframe) {
        BigDecimal open = decimal(node, "open");
        BigDecimal high = decimal(node, "high");
        BigDecimal low = decimal(node, "low");
        BigDecimal close = decimal(node, "close");
        if (open == null || high == null || low == null || close == null) {
            return null;
        }
        Instant openTime = parseTime(node.path("datetime").asText(null), timeframe);
        if (openTime == null) {
            return null;
        }
        return new CandleBar(openTime, open, high, low, close,
                Decimals.nullToZero(decimal(node, "volume")));
    }

    /** Twelve Data returns UTC naive timestamps (or dates for daily/weekly intervals). */
    private Instant parseTime(String raw, Timeframe timeframe) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            if (raw.length() <= 10) {
                return LocalDate.parse(raw.trim()).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            String normalized = raw.trim().replace(' ', 'T');
            return LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC);
        } catch (Exception ex) {
            log.debug("Unparseable candle timestamp '{}' for {}", raw, timeframe);
            return null;
        }
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        String text = value.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text).setScale(Decimals.PRICE_SCALE, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Diagnostics
    // ---------------------------------------------------------------------------------------

    private void recordSuccess(long started, String detail) {
        lastStatus = new ProviderStatus(id(), true, ProviderHealth.OK, detail,
                System.currentTimeMillis() - started, null);
    }

    private void recordFailure(long started, String detail) {
        lastStatus = new ProviderStatus(id(), true, ProviderHealth.ERROR, detail,
                System.currentTimeMillis() - started, Instant.now().toString());
    }

    /** Minimal Jackson facade so the class does not depend on Spring's ObjectMapper wiring. */
    private static final class Json {
        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();

        private Json() {
        }

        static JsonNode read(String body) {
            if (body == null || body.isBlank()) {
                return null;
            }
            try {
                return MAPPER.readTree(body);
            } catch (Exception ex) {
                return null;
            }
        }
    }

    /** Exposed for diagnostics: the exact provider symbol each instrument resolves to. */
    public Map<String, String> symbolMap(List<Instrument> instruments) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Instrument instrument : instruments) {
            ProviderSymbol mapping = resolve(instrument);
            map.put(instrument.getSymbol(), mapping == null
                    ? "unsupported"
                    : (mapping.exchange() == null ? mapping.symbol() : mapping.exchange() + ":" + mapping.symbol()));
        }
        return Collections.unmodifiableMap(map);
    }
}

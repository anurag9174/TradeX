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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Live market data via <a href="https://docs.delta.exchange">Delta Exchange</a>.
 *
 * <p>Delta's market-data endpoints are <b>public</b> - no API key, no request signing. An API key
 * only becomes necessary for real order execution, which TradeX deliberately does not do. That
 * makes this provider the safe way to put real prices behind the simulator: charts, quotes and
 * paper fills all run on Delta's real tape.</p>
 *
 * <h2>Coverage</h2>
 * Delta Exchange lists <em>crypto derivatives only</em> (perpetual futures, futures and options)
 * and quotes them in USD/USDT. Equities ({@code NSE:RELIANCE}) and Indian indices
 * ({@code NSE:NIFTY50}) are not listed there; those instruments surface as
 * "live price unavailable" instead of being faked. New Delta instruments carry a canonical
 * {@code DELTA:BTCUSD} reference; operators can map any instrument explicitly through
 * {@code tradex.marketdata.symbol-overrides}.</p>
 *
 * <h2>Candles</h2>
 * {@code GET /v2/history/candles} returns at most 4000 bars per request, newest first. This adapter
 * asks for an exact window, reverses the response into ascending order and trims to the requested
 * limit. The currently forming bar is included by the exchange and is passed through - the live
 * chart therefore never mixes a simulated bar into real history.</p>
 *
 * <h2>Failure behaviour</h2>
 * On a provider error the adapter returns no data for the affected calls and records the failure
 * with an explicit status. Callers label the instrument unavailable; simulated prices are never
 * substituted while a live provider is selected.
 */
@Component
public class DeltaExchangeMarketDataProvider implements MarketDataProvider {

    public static final String PROVIDER_ID = "DELTA";

    /** Delta rejects any single candle response larger than this; we stay well below it. */
    static final int MAX_CANDLES_PER_REQUEST = 4000;

    private static final Logger log = LoggerFactory.getLogger(DeltaExchangeMarketDataProvider.class);

    private final MarketDataProperties properties;
    private final RestClient client;

    private volatile ProviderStatus lastStatus = new ProviderStatus(
            PROVIDER_ID, true, ProviderHealth.OK,
            "Public market-data endpoint; no API key required.", 0L, null);

    public DeltaExchangeMarketDataProvider(MarketDataProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(properties.getHttpTimeoutMillis());
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .baseUrl(properties.getDeltaBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return "Delta Exchange (live market data)";
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
        // The public market-data API needs no key; a blank base URL is the only misconfiguration.
        return properties.getDeltaBaseUrl() != null && !properties.getDeltaBaseUrl().isBlank();
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
        // Delta lists no indices; an index can only be served when the operator maps it explicitly.
        return instrument.getAssetClass() != AssetClass.INDEX || mapping.explicitOverride();
    }

    @Override
    public String unsupportedReason(Instrument instrument) {
        if (!isConfigured()) {
            return "Delta Exchange base URL is not configured (tradex.marketdata.delta-base-url).";
        }
        ProviderSymbol mapping = resolve(instrument);
        if (mapping == null) {
            return "Delta Exchange lists crypto derivatives only; no symbol mapping for "
                    + instrument.getSymbol() + " (canonical " + instrument.getProviderSymbol() + "). "
                    + "Map it explicitly with tradex.marketdata.symbol-overrides." + instrument.getSymbol()
                    + " if it is listed there.";
        }
        if (instrument.getAssetClass() == AssetClass.INDEX && !mapping.explicitOverride()) {
            return "Delta Exchange does not list index products for " + instrument.getExchange()
                    + ". Enable it with tradex.marketdata.symbol-overrides." + instrument.getSymbol()
                    + " only if you know it exists on Delta.";
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
            // Delta's ticker endpoint is per symbol; the watchlist only contains a handful of
            // mapped instruments, so the fan-out stays tiny.
            for (Instrument instrument : supported) {
                JsonNode node = fetchTicker(requestSymbol(instrument));
                QuoteSnapshot snapshot = node == null ? null : toQuote(instrument, node);
                if (snapshot != null) {
                    result.put(instrument.getId(), snapshot);
                }
            }
            if (result.isEmpty()) {
                recordFailure(started, "no quotes returned");
            } else {
                recordSuccess(started, result.size() + " quotes");
            }
        } catch (Exception ex) {
            recordFailure(started, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            log.warn("Delta Exchange quote fetch failed: {}", ex.getMessage());
        }
        return result;
    }

    private JsonNode fetchTicker(String symbol) {
        String body = client.get()
                .uri("/v2/tickers/" + symbol)
                .retrieve()
                .body(String.class);
        JsonNode root = Json.read(body);
        if (root == null) {
            return null;
        }
        if (root.has("success") && !root.path("success").asBoolean(true)) {
            return null;
        }
        JsonNode result = root.path("result");
        return result.isObject() ? result : null;
    }

    @Override
    public List<CandleBar> candles(Instrument instrument, Timeframe timeframe, int limit) {
        if (!supports(instrument)) {
            return List.of();
        }
        int wanted = Math.min(Math.max(limit, 1), MAX_CANDLES_PER_REQUEST);
        long started = System.currentTimeMillis();
        try {
            long end = Instant.now().getEpochSecond();
            // Two spare buckets so trimming to `wanted` still works when a bucket is missing.
            long start = end - timeframe.seconds() * (wanted + 2L);
            String body = client.get()
                    .uri(UriComponentsBuilder.fromPath("/v2/history/candles")
                            .queryParam("resolution", resolution(timeframe))
                            .queryParam("symbol", requestSymbol(instrument))
                            .queryParam("start", start)
                            .queryParam("end", end)
                            .build(true).toUriString())
                    .retrieve()
                    .body(String.class);
            JsonNode root = Json.read(body);
            if (root == null) {
                recordFailure(started, "empty candle response");
                return List.of();
            }
            if (root.has("success") && !root.path("success").asBoolean(true)) {
                recordFailure(started, "candle error: " + root.path("error").asText("unknown"));
                return List.of();
            }
            JsonNode values = root.path("result");
            if (!values.isArray()) {
                recordFailure(started, "candle response has no result array");
                return List.of();
            }
            List<CandleBar> bars = new ArrayList<>(values.size());
            for (JsonNode node : values) {
                CandleBar bar = toBar(node);
                if (bar != null) {
                    bars.add(bar);
                }
            }
            // Delta answers newest-first; the rest of the platform expects ascending order.
            bars.sort((a, b) -> a.openTime().compareTo(b.openTime()));
            int size = bars.size();
            List<CandleBar> trimmed = size <= wanted ? bars : new ArrayList<>(bars.subList(size - wanted, size));
            recordSuccess(started, trimmed.size() + " candles");
            return trimmed;
        } catch (Exception ex) {
            recordFailure(started, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            log.warn("Delta Exchange candle fetch failed for {}: {}", instrument.getSymbol(), ex.getMessage());
            return List.of();
        }
    }

    @Override
    public ProviderStatus status() {
        return lastStatus;
    }

    // ---------------------------------------------------------------------------------------
    // Symbol mapping
    // ---------------------------------------------------------------------------------------

    private record ProviderSymbol(String symbol, boolean explicitOverride) {
    }

    /**
     * Translates the canonical {@code VENUE:TICKER} reference into Delta's request symbol.
     * Returns {@code null} when this adapter cannot express the instrument at all.
     */
    ProviderSymbol resolve(Instrument instrument) {
        Map<String, String> overrides = properties.getSymbolOverrides();
        String override = overrides.get(instrument.getSymbol());
        if (override != null && !override.isBlank()) {
            String trimmed = override.trim();
            // Allow both "BTCUSD" and an explicit "DELTA:BTCUSD" style override.
            int separator = trimmed.indexOf(':');
            return new ProviderSymbol(separator > 0 ? trimmed.substring(separator + 1).trim() : trimmed, true);
        }
        String canonical = instrument.getProviderSymbol();
        if (canonical == null || canonical.isBlank()) {
            return null;
        }
        int separator = canonical.indexOf(':');
        if (separator <= 0 || separator == canonical.length() - 1) {
            // A bare ticker is passed through untouched.
            return new ProviderSymbol(canonical.trim(), false);
        }
        String venue = canonical.substring(0, separator).trim().toUpperCase();
        String ticker = canonical.substring(separator + 1).trim();
        return switch (venue) {
            case "DELTA" -> new ProviderSymbol(ticker, false);
            // Crypto pairs without an INR/equity venue prefix are Delta-style symbols (BTCUSD).
            // TradeX's synthetic BTC/INR instruments deliberately do NOT resolve: Delta has no INR
            // pairs and pretending otherwise would print USD prices under an INR label.
            case "CRYPTO" -> ticker.contains("/") ? null : new ProviderSymbol(ticker, false);
            default -> null;
        };
    }

    private String requestSymbol(Instrument instrument) {
        ProviderSymbol mapping = resolve(instrument);
        return mapping == null ? instrument.getSymbol() : mapping.symbol();
    }

    /** Delta's resolution codes; every TradeX timeframe maps 1:1. */
    String resolution(Timeframe timeframe) {
        return switch (timeframe) {
            case M1 -> "1m";
            case M5 -> "5m";
            case M15 -> "15m";
            case M30 -> "30m";
            case H1 -> "1h";
            case H4 -> "4h";
            case D1 -> "1d";
            case W1 -> "1w";
        };
    }

    // ---------------------------------------------------------------------------------------
    // Response mapping
    // ---------------------------------------------------------------------------------------

    private QuoteSnapshot toQuote(Instrument instrument, JsonNode node) {
        BigDecimal last = decimal(node, "close");
        if (last == null) {
            last = decimal(node, "mark_price");
        }
        if (last == null) {
            return null;
        }
        BigDecimal previousClose = derivePreviousClose(last, decimal(node, "ltp_change_24h"));
        BigDecimal bid = node.path("quotes").isObject() ? decimal(node.path("quotes"), "best_bid") : null;
        BigDecimal ask = node.path("quotes").isObject() ? decimal(node.path("quotes"), "best_ask") : null;
        return new QuoteSnapshot(
                instrument.getSymbol(),
                Decimals.toTick(last, instrument.getTickSize()),
                previousClose == null ? null : Decimals.toTick(previousClose, instrument.getTickSize()),
                tickOrNull(decimal(node, "open"), instrument),
                tickOrNull(decimal(node, "high"), instrument),
                tickOrNull(decimal(node, "low"), instrument),
                Decimals.nullToZero(decimal(node, "volume")),
                tickOrNull(bid, instrument),
                tickOrNull(ask, instrument),
                MarketDataSource.LIVE,
                instant(node.path("time").asText(null)));
    }

    /**
     * Delta reports the 24h change in percent rather than the previous close. Deriving the
     * previous close from the exchange's own published change keeps the displayed number exactly
     * consistent with Delta's tape instead of inventing a second quote.
     */
    private BigDecimal derivePreviousClose(BigDecimal last, BigDecimal changePercent) {
        if (changePercent == null || changePercent.compareTo(new BigDecimal("-99.99")) <= 0) {
            return null;
        }
        BigDecimal factor = BigDecimal.ONE.add(changePercent.divide(Decimals.HUNDRED, Decimals.MC), Decimals.MC);
        if (factor.signum() <= 0) {
            return null;
        }
        return last.divide(factor, Decimals.PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private CandleBar toBar(JsonNode node) {
        BigDecimal open = decimal(node, "open");
        BigDecimal high = decimal(node, "high");
        BigDecimal low = decimal(node, "low");
        BigDecimal close = decimal(node, "close");
        JsonNode timeNode = node.get("time");
        if (open == null || high == null || low == null || close == null || timeNode == null
                || !timeNode.canConvertToLong()) {
            return null;
        }
        long epochSeconds = timeNode.asLong();
        if (epochSeconds <= 0) {
            return null;
        }
        return new CandleBar(Instant.ofEpochSecond(epochSeconds), open, high, low, close,
                Decimals.nullToZero(decimal(node, "volume")));
    }

    private BigDecimal tickOrNull(BigDecimal value, Instrument instrument) {
        return value == null ? null : Decimals.toTick(value, instrument.getTickSize());
    }

    /** Delta timestamps are ISO-8601 instants, e.g. {@code 2026-10-14T16:30:08.057511855Z}. */
    private Instant instant(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(raw.trim());
        } catch (Exception ex) {
            log.debug("Unparseable Delta timestamp '{}'", raw);
            return Instant.now();
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
            map.put(instrument.getSymbol(), mapping == null ? "unsupported" : mapping.symbol());
        }
        return Collections.unmodifiableMap(map);
    }
}

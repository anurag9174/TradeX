package com.tradex.marketdata;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tradex.config.MarketDataProperties;
import com.tradex.entity.Instrument;
import com.tradex.enums.AssetClass;
import com.tradex.enums.MarketDataSource;
import com.tradex.enums.Timeframe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Delta Exchange adapter against a local HTTP stub that mimics the real response shapes
 * (verified against {@code api.india.delta.exchange}: ticker at {@code /v2/tickers/{symbol}},
 * candles at {@code /v2/history/candles}, newest bar first).
 */
class DeltaExchangeMarketDataProviderTest {

    private HttpServer server;
    private DeltaExchangeMarketDataProvider provider;
    private final List<String> requestedUris = new ArrayList<>();
    private final Map<String, String> routeUrls = new HashMap<>();
    private final Map<String, Integer> routeStatus = new HashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        MarketDataProperties properties = new MarketDataProperties();
        properties.setDeltaBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setHttpTimeoutMillis(3000);
        provider = new DeltaExchangeMarketDataProvider(properties);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String uri = exchange.getRequestURI().toString();
        requestedUris.add(uri);
        String path = exchange.getRequestURI().getPath();
        String body = routeUrls.get(path);
        int status = routeStatus.getOrDefault(path, 200);
        if (body == null) {
            body = "{\"success\":false,\"error\":{\"code\":\"not_found\"}}";
            status = 404;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    private static Instrument btcUsd() {
        Instrument instrument = new Instrument();
        instrument.setId(7L);
        instrument.setSymbol("BTCUSD");
        instrument.setName("Bitcoin / US Dollar Perpetual");
        instrument.setExchange("DELTA");
        instrument.setAssetClass(AssetClass.CRYPTO);
        instrument.setCurrency("USD");
        instrument.setProviderSymbol("DELTA:BTCUSD");
        instrument.setTickSize(new BigDecimal("0.5"));
        return instrument;
    }

    private static Instrument cryptoWithSlash() {
        Instrument instrument = btcUsd();
        instrument.setId(8L);
        instrument.setSymbol("BTCINR");
        instrument.setProviderSymbol("CRYPTO:BTC/INR");
        instrument.setCurrency("INR");
        return instrument;
    }

    private static Instrument nseEquity() {
        Instrument instrument = btcUsd();
        instrument.setId(9L);
        instrument.setSymbol("RELIANCE");
        instrument.setProviderSymbol("NSE:RELIANCE");
        instrument.setExchange("NSE");
        instrument.setCurrency("INR");
        instrument.setAssetClass(AssetClass.EQUITY);
        return instrument;
    }

    private static String tickerBody() {
        return """
                {"success":true,"result":{
                  "symbol":"BTCUSD",
                  "mark_price":"82044.58",
                  "close":"82041.5",
                  "open":"81700.0",
                  "high":"82400.0",
                  "low":"81500.0",
                  "volume":"1234.56",
                  "ltp_change_24h":"0.4364",
                  "quotes":{"best_bid":"82040.5","best_ask":"82042.5","ask_size":"1","bid_size":"2"},
                  "time":"2026-10-14T16:30:08.057511855Z"
                }}""";
    }

    private static String candlesBody() {
        // Newest first, exactly as Delta answers.
        return """
                {"success":true,"result":[
                  {"time":1792086960,"open":"82000.0","high":"82100.0","low":"81900.0","close":"82041.5","volume":"10.5"},
                  {"time":1792086900,"open":"81900.0","high":"82050.0","low":"81850.0","close":"82000.0","volume":"11.0"},
                  {"time":1792086840,"open":"81800.0","high":"81950.0","low":"81750.0","close":"81900.0","volume":"12.0"}
                ]}""";
    }

    // ---------------------------------------------------------------------------------------
    // Symbol mapping
    // ---------------------------------------------------------------------------------------

    @Test
    void supportsDeltaAndSlashlessCryptoReferencesOnly() {
        assertThat(provider.supports(btcUsd())).isTrue();
        assertThat(provider.supports(cryptoWithSlash())).isFalse();
        assertThat(provider.supports(nseEquity())).isFalse();

        // A USD/INR-style canonical reference is never mapped on top of Delta's USD tape.
        assertThat(provider.unsupportedReason(cryptoWithSlash()))
                .contains("crypto derivatives only");
        assertThat(provider.unsupportedReason(nseEquity()))
                .contains("symbol-overrides");
    }

    @Test
    void operatorOverridesWinOverCanonicalReferences() {
        Instrument index = nseEquity();
        index.setSymbol("NIFTY50");
        index.setAssetClass(AssetClass.INDEX);
        index.setProviderSymbol("NSE:NIFTY50");

        MarketDataProperties properties = new MarketDataProperties();
        properties.setDeltaBaseUrl("http://127.0.0.1:1");
        properties.setSymbolOverrides(Map.of("NIFTY50", "BTCUSD"));
        DeltaExchangeMarketDataProvider mapped = new DeltaExchangeMarketDataProvider(properties);

        assertThat(mapped.supports(index)).isTrue();
        assertThat(mapped.symbolMap(List.of(index))).containsEntry("NIFTY50", "BTCUSD");
    }

    @Test
    void canonicalMappingIsExposedForDiagnostics() {
        assertThat(provider.symbolMap(List.of(btcUsd(), cryptoWithSlash(), nseEquity())))
                .containsEntry("BTCUSD", "BTCUSD")
                .containsEntry("BTCINR", "unsupported")
                .containsEntry("RELIANCE", "unsupported");
    }

    @Test
    void resolutionCodesMatchDeltaVocabulary() {
        assertThat(provider.resolution(Timeframe.M1)).isEqualTo("1m");
        assertThat(provider.resolution(Timeframe.M5)).isEqualTo("5m");
        assertThat(provider.resolution(Timeframe.H4)).isEqualTo("4h");
        assertThat(provider.resolution(Timeframe.D1)).isEqualTo("1d");
        assertThat(provider.resolution(Timeframe.W1)).isEqualTo("1w");
    }

    // ---------------------------------------------------------------------------------------
    // Quotes
    // ---------------------------------------------------------------------------------------

    @Test
    void quoteMapsLastTradedPriceAndDerivesPreviousCloseFromTheExchangesOwnChange() {
        routeUrls.put("/v2/tickers/BTCUSD", tickerBody());

        Optional<QuoteSnapshot> fetched = provider.quote(btcUsd());

        assertThat(fetched).isPresent();
        QuoteSnapshot quote = fetched.get();
        // `close` is the last traded price; mark_price must not silently replace it.
        assertThat(quote.lastPrice()).isEqualByComparingTo("82041.5");
        // 82041.5 / 1.004364 ~= 81685.03, snapped to the 0.5 tick grid.
        assertThat(quote.previousClose()).isEqualByComparingTo("81685.0");
        assertThat(quote.dayOpen()).isEqualByComparingTo("81700.0");
        assertThat(quote.dayHigh()).isEqualByComparingTo("82400.0");
        assertThat(quote.dayLow()).isEqualByComparingTo("81500.0");
        assertThat(quote.bid()).isEqualByComparingTo("82040.5");
        assertThat(quote.ask()).isEqualByComparingTo("82042.5");
        assertThat(quote.dayVolume()).isEqualByComparingTo("1234.56");
        assertThat(quote.source()).isEqualTo(MarketDataSource.LIVE);
        assertThat(quote.asOf()).isEqualTo(Instant.parse("2026-10-14T16:30:08.057511855Z"));
        assertThat(provider.status().health()).isEqualTo(MarketDataProvider.ProviderHealth.OK);
    }

    @Test
    void providerErrorsSurfaceAsAnExplicitFailureNotAnInventedPrice() {
        routeUrls.put("/v2/tickers/BTCUSD", "{\"success\":false,\"error\":{\"code\":\"server_error\"}}");
        routeStatus.put("/v2/tickers/BTCUSD", 500);

        assertThat(provider.quote(btcUsd())).isEmpty();
        assertThat(provider.status().health()).isEqualTo(MarketDataProvider.ProviderHealth.ERROR);
        assertThat(provider.status().lastErrorAt()).isNotNull();
    }

    @Test
    void quoteReturnsEmptyWhenThePayloadHasNoUsableLastPrice() {
        routeUrls.put("/v2/tickers/BTCUSD", "{\"success\":true,\"result\":{\"symbol\":\"BTCUSD\"}}");

        assertThat(provider.quote(btcUsd())).isEmpty();
    }

    @Test
    void quotesFanOutPerSymbolAndSkipUnsupportedInstruments() {
        routeUrls.put("/v2/tickers/BTCUSD", tickerBody());

        Map<Long, QuoteSnapshot> quotes = provider.quotes(List.of(btcUsd(), nseEquity()));

        assertThat(quotes).containsOnlyKeys(7L);
        assertThat(requestedUris).hasSize(1);
    }

    // ---------------------------------------------------------------------------------------
    // Candles
    // ---------------------------------------------------------------------------------------

    @Test
    void candlesAreReversedIntoAscendingOrderAndTrimmedToTheRequestedLimit() {
        routeUrls.put("/v2/history/candles", candlesBody());

        List<CandleBar> bars = provider.candles(btcUsd(), Timeframe.M5, 2);

        assertThat(bars).hasSize(2);
        assertThat(bars.get(0).openTime()).isEqualTo(Instant.ofEpochSecond(1792086900L));
        assertThat(bars.get(1).openTime()).isEqualTo(Instant.ofEpochSecond(1792086960L));
        assertThat(bars.get(1).close()).isEqualByComparingTo("82041.5");
        assertThat(bars.get(0).volume()).isEqualByComparingTo("11.0");

        String uri = requestedUris.get(0);
        assertThat(uri).contains("resolution=5m", "symbol=BTCUSD", "start=", "end=");
    }

    @Test
    void tooShortResponsesArePassedThroughWithoutPadding() {
        routeUrls.put("/v2/history/candles", """
                {"success":true,"result":[
                  {"time":1792086960,"open":"82000.0","high":"82100.0","low":"81900.0","close":"82041.5","volume":"10.5"}
                ]}""");

        // A new listing legitimately has fewer bars than requested; the adapter must not invent
        // the missing history.
        assertThat(provider.candles(btcUsd(), Timeframe.M5, 500)).hasSize(1);
    }

    @Test
    void candleFailuresRecordAnErrorStatusAndReturnNothing() {
        routeUrls.put("/v2/history/candles", "{\"success\":false,\"error\":{\"code\":\"invalid_resolution\"}}");

        assertThat(provider.candles(btcUsd(), Timeframe.M5, 10)).isEmpty();
        assertThat(provider.status().health()).isEqualTo(MarketDataProvider.ProviderHealth.ERROR);
    }

    @Test
    void unsupportedInstrumentsNeverHitTheNetwork() {
        assertThat(provider.candles(nseEquity(), Timeframe.M5, 10)).isEmpty();
        assertThat(provider.quotes(List.of(nseEquity()))).isEmpty();
        assertThat(requestedUris).isEmpty();
    }

    @Test
    void identifiersDescribeTheAdapterHonestly() {
        assertThat(provider.id()).isEqualTo("DELTA");
        assertThat(provider.live()).isTrue();
        assertThat(provider.source()).isEqualTo(MarketDataSource.LIVE);
        assertThat(provider.isConfigured()).isTrue();
        assertThat(provider.displayName()).contains("Delta Exchange");
    }
}

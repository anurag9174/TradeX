package com.tradex.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradex.support.ApiTestSupport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The indicators endpoint contract the chart depends on: every requested specification comes back
 * under the exact key the client sent ({@code macd:12-26-9}, not {@code macd:12:26:9}), the series
 * are index-aligned with the shared {@code times} axis, and Bollinger bands honour the requested
 * multiplier instead of silently collapsing onto the middle line.
 */
class MarketDataApiTest extends ApiTestSupport {

    private static final String INDICATORS = "/api/market/indicators/RELIANCE"
            + "?timeframe=15m&limit=120&indicators=sma:20,ema:50,macd:12-26-9,bb:20-2,rsi:14";

    @Test
    void seriesAreKeyedByTheExactRequestedSpecification() throws Exception {
        Session session = register();
        JsonNode body = expectOk(get(INDICATORS, session.token()));

        assertThat(body.path("series").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("sma:20", "ema:50", "macd:12-26-9", "bb:20-2", "rsi:14");
    }

    @Test
    void everySeriesIsIndexAlignedWithTheSharedTimesAxis() throws Exception {
        Session session = register();
        JsonNode body = expectOk(get(INDICATORS, session.token()));

        int expected = body.path("candleCount").asInt();
        assertThat(expected).isPositive();
        assertThat(body.path("times")).hasSize(expected);

        for (JsonNode series : body.path("series")) {
            String key = series.path("key").asText();
            assertThat(series.path("values")).as("%s values", key).hasSize(expected);
            for (String field : List.of("upper", "middle", "lower", "signal", "histogram")) {
                if (series.hasNonNull(field)) {
                    assertThat(series.path(field)).as("%s.%s", key, field).hasSize(expected);
                }
            }
        }

        // The axis is ascending epoch seconds - the chart's x-scale contract.
        JsonNode times = body.path("times");
        for (int i = 1; i < times.size(); i++) {
            assertThat(times.get(i).asLong()).isGreaterThan(times.get(i - 1).asLong());
        }
    }

    @Test
    void bollingerBandsUseTheRequestedMultiplierAndStraddleTheMiddle() throws Exception {
        Session session = register();
        JsonNode body = expectOk(get(INDICATORS, session.token()));
        JsonNode bands = body.path("series").path("bb:20-2");

        assertThat(bands.path("kind").asText()).isEqualTo("band");
        assertThat(bands.path("params").path("multiplier").decimalValue())
                .isEqualByComparingTo(new BigDecimal("2"));

        int last = lastIndexPopulatedFor(bands, "upper", "middle", "lower", "values");
        assertThat(last).as("Bollinger bands should have warmed up within 120 candles").isGreaterThan(0);
        assertThat(bands.path("upper").get(last).decimalValue())
                .isGreaterThan(bands.path("middle").get(last).decimalValue());
        assertThat(bands.path("middle").get(last).decimalValue())
                .isGreaterThan(bands.path("lower").get(last).decimalValue());
        // The DTO documents `values` as the upper band for a band series; the chart draws all three.
        assertThat(bands.path("values").get(last).decimalValue())
                .isEqualByComparingTo(bands.path("upper").get(last).decimalValue());
    }

    @Test
    void macdHistogramIsTheDifferenceBetweenLineAndSignal() throws Exception {
        Session session = register();
        JsonNode body = expectOk(get(INDICATORS, session.token()));
        JsonNode macd = body.path("series").path("macd:12-26-9");

        int last = lastIndexPopulatedFor(macd, "values", "signal", "histogram");
        assertThat(last).isGreaterThan(0);
        BigDecimal line = macd.path("values").get(last).decimalValue();
        BigDecimal signal = macd.path("signal").get(last).decimalValue();
        BigDecimal histogram = macd.path("histogram").get(last).decimalValue();

        assertThat(histogram.subtract(line.subtract(signal)).abs())
                .isLessThanOrEqualTo(new BigDecimal("0.000001"));
    }

    @Test
    void unknownIndicatorIsRejectedWithAValidationError() throws Exception {
        Session session = register();
        Response response = get("/api/market/indicators/RELIANCE?timeframe=15m&limit=50&indicators=xyz:9",
                session.token());

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().path("code").asText()).isEqualTo("BAD_REQUEST");
        assertThat(response.json().path("message").asText()).contains("Unsupported indicator");
    }

    @Test
    void marketStatusDescribesTheDataSourceNotTheProvider() throws Exception {
        JsonNode status = expectOk(get("/api/market/status", null));

        // `dataSource` must be the honesty enum (SIMULATED / LIVE) - never the provider id. The UI
        // keys its banner off this value, and a provider id would silently read as "not live".
        String dataSource = status.path("dataSource").asText();
        assertThat(dataSource).isIn("SIMULATED", "LIVE");

        // The test profile runs the simulated feed.
        assertThat(dataSource).isEqualTo("SIMULATED");
        assertThat(status.path("simulated").asBoolean()).isTrue();
        assertThat(status.path("dataSourceLabel").asText()).contains("SIMULATED");

        // The provider still travels in its own field.
        assertThat(status.path("provider").asText()).isNotBlank();
        assertThat(status.path("sessions").path("mode").asText()).isEqualTo("SIMULATED");
    }

    // =======================================================================================
    // Helpers
    // =======================================================================================

    private JsonNode expectOk(Response response) {
        assertThat(response.status()).as("unexpected body: %s", response.raw()).isEqualTo(200);
        return response.json();
    }

    /** Last index where every named array field of the series is populated (past all warm-up nulls). */
    private static int lastIndexPopulatedFor(JsonNode series, String... fields) {
        int size = series.path("values").size();
        for (int i = size - 1; i >= 0; i--) {
            boolean complete = true;
            for (String field : fields) {
                JsonNode array = series.path(field);
                if (i >= array.size() || array.get(i).isNull()) {
                    complete = false;
                    break;
                }
            }
            if (complete) {
                return i;
            }
        }
        return -1;
    }
}

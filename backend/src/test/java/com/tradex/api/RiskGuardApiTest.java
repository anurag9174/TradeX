package com.tradex.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradex.support.ApiTestSupport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The daily-loss guard and the behavioural side of stop-loss planning.
 *
 * <p>Two things must hold at once: a breached account cannot open new risk, and a protective stop
 * that exists to <em>reduce</em> risk must still be allowed to fire.</p>
 */
class RiskGuardApiTest extends ApiTestSupport {

    @Test
    void aBreachedDailyLossLimitBlocksNewTradesButNotProtectiveExits() throws Exception {
        Session session = register();
        String token = session.token();

        // 1) Book a round-trip loss first. Charges alone make an immediate buy+sell round trip a
        //    guaranteed loss, so the breach that follows is deterministic - not a price lottery.
        assertThat(post("/api/orders", token, Map.of(
                "symbol", "HDFCBANK", "side", "BUY", "orderType", "MARKET", "quantity", 10)).status())
                .isEqualTo(201);
        assertThat(post("/api/orders", token, Map.of(
                "symbol", "HDFCBANK", "side", "SELL", "orderType", "MARKET", "quantity", 10)).status())
                .isEqualTo(201);
        assertThat(get("/api/portfolio", token).json().path("realizedPnl").decimalValue())
                .isLessThan(BigDecimal.ZERO);

        // 2) Stay in the market with a protective stop deliberately above the market, so the next
        //    engine pass sees a breached stop and exits. (Position levels are risk metadata; the
        //    API does not enforce which side of the market they sit on.)
        JsonNode buy = placedOrder(post("/api/orders", token, Map.of(
                "symbol", "HDFCBANK", "side", "BUY", "orderType", "MARKET", "quantity", 5,
                "positionStopLoss", price("HDFCBANK").multiply(new BigDecimal("1.10"))
                        .setScale(2, RoundingMode.HALF_UP))));
        assertThat(buy.path("status").asText()).isEqualTo("EXECUTED");
        assertThat(holding(token, "HDFCBANK").path("stopLossPrice").decimalValue())
                .isGreaterThan(BigDecimal.ZERO);

        // 3) Switch on a ₹1 daily loss limit: the realised loss already breaches it.
        Response settings = put("/api/risk/settings", token, Map.of(
                "dailyLossLimit", 1, "dailyLossLimitEnabled", true));
        assertThat(settings.status()).isEqualTo(200);

        JsonNode account = get("/api/account", token).json();
        assertThat(account.path("tradingBlocked").asBoolean()).isTrue();
        assertThat(account.path("dailyLossLimitEnabled").asBoolean()).isTrue();

        // 3) A new risk-increasing order is refused with the guard's code.
        Response blocked = post("/api/orders", token, Map.of(
                "symbol", "HDFCBANK", "side", "BUY", "orderType", "MARKET", "quantity", 1));
        assertThat(blocked.status()).isEqualTo(403);
        assertThat(blocked.json().path("code").asText()).isEqualTo("TRADING_BLOCKED");
        assertMoney(get("/api/account/balance", token).json(), "availableCash",
                account.path("availableCash").decimalValue());

        // 4) The protective exit still fires: it reduces risk, so it bypasses the guard.
        matchingEngine.workProtectiveLevels();
        assertThat(get("/api/portfolio/holdings", token).json()).isEmpty();

        // Four HDFCBANK trades: the round trip, the re-entry and the protective exit (newest first).
        JsonNode trades = get("/api/trades?symbol=HDFCBANK", token).json().path("content");
        assertThat(trades).hasSize(4);
        assertThat(trades.get(0).path("side").asText()).isEqualTo("SELL");
        assertThat(trades.get(0).path("quantity").decimalValue()).isEqualByComparingTo("5");

        // 5) Raising the limit and clearing the breach lets the account trade again.
        Response cleared = put("/api/risk/settings", token, Map.of(
                "dailyLossLimit", 100000, "clearBreach", true));
        assertThat(cleared.status()).isEqualTo(200);
        assertThat(get("/api/account", token).json().path("tradingBlocked").asBoolean()).isFalse();

        Response resumed = post("/api/orders", token, Map.of(
                "symbol", "HDFCBANK", "side", "BUY", "orderType", "MARKET", "quantity", 1));
        assertThat(resumed.status()).isEqualTo(201);
        assertThat(resumed.order().path("status").asText()).isEqualTo("EXECUTED");
    }

    @Test
    void protectiveExitIsBookedRoughlyAtTheMarketPriceOfTheWorkingFeed() throws Exception {
        Session session = register();
        String token = session.token();

        BigDecimal market = price("WIPRO");
        assertThat(post("/api/orders", token, Map.of(
                "symbol", "WIPRO", "side", "BUY", "orderType", "MARKET", "quantity", 4,
                "positionStopLoss", market.multiply(new BigDecimal("0.99")).setScale(2, RoundingMode.HALF_UP),
                "positionTarget", market.multiply(new BigDecimal("5.00")).setScale(2, RoundingMode.HALF_UP)))
                .status()).isEqualTo(201);

        // Stop below the market: the holding must survive an engine pass untouched.
        matchingEngine.workProtectiveLevels();
        assertThat(get("/api/portfolio/holdings", token).json()).hasSize(1);

        // Now move the stop above the market on the stored position and pass again.
        var instrument = instrument("WIPRO");
        var holding = holdingRepository
                .findByAccountIdAndInstrumentId(session.accountId(), instrument.getId()).orElseThrow();
        holding.setStopLossPrice(price("WIPRO").multiply(new BigDecimal("1.10"))
                .setScale(2, RoundingMode.HALF_UP));
        holdingRepository.saveAndFlush(holding);

        matchingEngine.workProtectiveLevels();
        assertThat(get("/api/portfolio/holdings", token).json()).isEmpty();

        JsonNode trades = get("/api/trades?symbol=WIPRO", token).json().path("content");
        assertThat(trades).hasSize(2);
        // The auto-exit is a market sell, priced by the synthetic feed like any other fill.
        JsonNode exit = trades.get(0);
        assertThat(exit.path("side").asText()).isEqualTo("SELL");
        assertThat(exit.path("price").decimalValue()).isGreaterThan(BigDecimal.ZERO);
        assertThat(exit.path("quantity").decimalValue()).isEqualByComparingTo("4");
    }

    // =======================================================================================

    private JsonNode placedOrder(Response response) {
        assertThat(response.status()).as("place order: %s", response.raw()).isEqualTo(201);
        return response.order();
    }

    private JsonNode holding(String token, String symbol) throws Exception {
        for (JsonNode holding : get("/api/portfolio/holdings", token).json()) {
            if (symbol.equals(holding.path("symbol").asText())) {
                return holding;
            }
        }
        throw new AssertionError("No holding for " + symbol);
    }

    private static void assertMoney(JsonNode node, String field, BigDecimal expected) {
        assertThat(node.path(field).decimalValue()).as("field %s", field).isEqualByComparingTo(expected);
    }
}

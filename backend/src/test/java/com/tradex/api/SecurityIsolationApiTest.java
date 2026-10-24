package com.tradex.api;

import com.tradex.support.ApiTestSupport;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authentication and per-user isolation.
 *
 * <p>The API is single-tenant per token: one user's orders, trades and portfolio must be invisible -
 * and untouchable - from another user's session.</p>
 */
class SecurityIsolationApiTest extends ApiTestSupport {

    @Test
    void endpointsRefuseAnonymousRequestsAndGarbageTokens() throws Exception {
        assertThat(get("/api/account/summary", null).status()).isEqualTo(401);
        assertThat(get("/api/orders", null).status()).isEqualTo(401);
        assertThat(get("/api/portfolio", null).status()).isEqualTo(401);
        assertThat(get("/api/trades", null).status()).isEqualTo(401);
        assertThat(get("/api/risk/summary", null).status()).isEqualTo(401);
        assertThat(get("/api/watchlist", null).status()).isEqualTo(401);

        // A structurally invalid bearer token is rejected by the JWT filter, not by chance.
        assertThat(get("/api/account/summary", "not-a-jwt").status()).isEqualTo(401);
        assertThat(get("/api/account/summary", "a.b.c").status()).isEqualTo(401);
    }

    @Test
    void publicMarketReadsStayPublicAndTaggedAsSimulated() throws Exception {
        var quote = get("/api/market/quote/RELIANCE", null);
        assertThat(quote.status()).isEqualTo(200);
        assertThat(quote.json().path("dataSourceLabel").asText()).contains("SIMULATED");
        assertThat(quote.json().path("lastPrice").decimalValue()).isGreaterThan(java.math.BigDecimal.ZERO);
    }

    @Test
    void oneUserCanNeverSeeOrCancelAnotherUsersOrder() throws Exception {
        Session alice = register("alice");
        Session bob = register("bob");

        long aliceOrderId = post("/api/orders", alice.token(), Map.of(
                "symbol", "RELIANCE", "side", "BUY", "orderType", "MARKET", "quantity", 5))
                .order().path("id").asLong();

        // Bob can read and cancel his own view of the world; Alice's order simply does not exist.
        assertThat(get("/api/orders/" + aliceOrderId, bob.token()).status()).isEqualTo(404);
        assertThat(delete("/api/orders/" + aliceOrderId, bob.token()).status()).isEqualTo(404);
        assertThat(get("/api/orders/" + aliceOrderId, alice.token()).status()).isEqualTo(200);

        // Bob's listings and portfolio are untouched by Alice's trade.
        assertThat(get("/api/orders", bob.token()).json().path("totalElements").asLong()).isZero();
        assertThat(get("/api/trades", bob.token()).json().path("totalElements").asLong()).isZero();
        assertThat(get("/api/portfolio/holdings", bob.token()).json()).isEmpty();
        assertThat(get("/api/account/summary", bob.token()).json()
                .path("availableCash").decimalValue()).isEqualByComparingTo("100000.00");
    }

    @Test
    void aTokenCannotBeReplayedAsAnotherIdentityByTamperingWithTheSubject() throws Exception {
        Session alice = register("alice");
        Session bob = register("bob");

        // Same-signature requirements: a token assembled by hand without the server's secret fails.
        String tampered = alice.token().substring(0, alice.token().lastIndexOf('.') + 1)
                + bob.token().substring(bob.token().lastIndexOf('.') + 1);
        assertThat(get("/api/account/summary", tampered).status()).isEqualTo(401);

        // Sanity: the untouched tokens still work.
        assertThat(get("/api/account/summary", alice.token()).status()).isEqualTo(200);
        assertThat(get("/api/account/summary", bob.token()).status()).isEqualTo(200);
    }
}

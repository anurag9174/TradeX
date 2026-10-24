package com.tradex.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradex.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "tradex.trading.default-account-currency=USD",
        "tradex.trading.initial-balance=10000.00"
})
class UsdCapitalApiTest extends ApiTestSupport {

    @Test
    void usdCapitalLimitsDefaultPersistenceAndUserIsolation() throws Exception {
        Session minimum = register("usd-min", new BigDecimal("100.00"));
        Session maximum = register("usd-max", new BigDecimal("1000000.00"));
        Session defaultAccount = register("usd-default");

        JsonNode minimumBalance = get("/api/account/balance", minimum.token()).json();
        JsonNode maximumBalance = get("/api/account/balance", maximum.token()).json();
        JsonNode defaultBalance = get("/api/account/balance", defaultAccount.token()).json();
        assertThat(minimumBalance.path("currency").asText()).isEqualTo("USD");
        assertThat(minimumBalance.path("initialBalance").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(maximumBalance.path("initialBalance").decimalValue()).isEqualByComparingTo("1000000.00");
        assertThat(defaultBalance.path("initialBalance").decimalValue()).isEqualByComparingTo("10000.00");
        assertThat(defaultBalance.path("availableCash").decimalValue()).isEqualByComparingTo("10000.00");

        Response login = post("/api/auth/login", null, Map.of(
                "identifier", minimum.username(), "password", PASSWORD));
        assertThat(login.status()).isEqualTo(200);
        assertThat(get("/api/account/balance", login.json().path("accessToken").asText()).json()
                .path("initialBalance").decimalValue()).isEqualByComparingTo("100.00");

        assertRejectedCapital("99.99");
        assertRejectedCapital("1000000.01");
        assertRejectedCapital("-1");
        assertRejectedCapital("0");
    }

    @Test
    void confirmedResetAlsoValidatesCapitalAndStartsANewUsdSession() throws Exception {
        Session session = register("usd-reset", new BigDecimal("500.00"));

        Response invalidReset = post("/api/account/reset", session.token(),
                Map.of("confirm", true, "initialCapital", new BigDecimal("1000000.01")));
        assertThat(invalidReset.status()).isEqualTo(400);

        Response reset = post("/api/account/reset", session.token(),
                Map.of("confirm", true, "initialCapital", new BigDecimal("25000.00")));
        assertThat(reset.status()).isEqualTo(200);
        assertThat(reset.json().path("currency").asText()).isEqualTo("USD");
        assertThat(reset.json().path("availableCash").decimalValue()).isEqualByComparingTo("25000.00");
        JsonNode balance = get("/api/account/balance", session.token()).json();
        assertThat(balance.path("availableCash").decimalValue()).isEqualByComparingTo("25000.00");
        assertThat(balance.path("initialBalance").decimalValue()).isEqualByComparingTo("25000.00");
    }

    private void assertRejectedCapital(String amount) throws Exception {
        String username = "usd-invalid" + System.nanoTime() % 100000;
        Response response = post("/api/auth/register", null, Map.of(
                "email", username + "@tradex.test",
                "username", username,
                "fullName", "Invalid USD capital",
                "password", PASSWORD,
                "initialCapital", new BigDecimal(amount)));
        assertThat(response.status()).as("capital %s should be rejected", amount).isEqualTo(400);
    }
}

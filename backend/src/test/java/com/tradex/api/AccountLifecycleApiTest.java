package com.tradex.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradex.entity.TransactionLedger;
import com.tradex.enums.LedgerEntryType;
import com.tradex.support.ApiTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registration, the one-time ₹1,00,000 grant, login and the account-restart lifecycle.
 *
 * <p>The double-grant regression lives here: an account must be funded exactly once, and a reset
 * must restore that funding through an explicit {@code ACCOUNT_RESET} ledger entry - never by
 * crediting a second {@code INITIAL_GRANT}.</p>
 */
class AccountLifecycleApiTest extends ApiTestSupport {

    @Test
    void registrationOpensOneAccountFundedExactlyOnceWithOneLakh() throws Exception {
        Session session = register();

        JsonNode summary = get("/api/account/summary", session.token()).json();
        assertThat(summary.path("availableCash").decimalValue()).isEqualByComparingTo("100000.00");
        assertThat(summary.path("portfolioValue").decimalValue()).isEqualByComparingTo("100000.00");
        assertThat(summary.path("investedValue").decimalValue()).isEqualByComparingTo("0");
        assertThat(summary.path("positionCount").asInt()).isZero();

        JsonNode balance = get("/api/account/balance", session.token()).json();
        assertThat(balance.path("initialBalance").decimalValue()).isEqualByComparingTo("100000.00");
        assertThat(balance.path("reservedCash").decimalValue()).isEqualByComparingTo("0");

        // The grant is a single immutable ledger row - the sole writer of cash.
        List<TransactionLedger> grants = ledgerOf(session).stream()
                .filter(entry -> entry.getEntryType() == LedgerEntryType.INITIAL_GRANT)
                .toList();
        assertThat(grants).hasSize(1);
        assertThat(grants.get(0).getAmount()).isEqualByComparingTo("100000.00");
    }

    @Test
    void usersCanChooseIndependentStartingCapitalAndInvalidAmountsAreRejected() throws Exception {
        Session custom = register("custom", new java.math.BigDecimal("25000.00"));
        Session defaults = register("default");

        assertThat(get("/api/account/balance", custom.token()).json()
                .path("initialBalance").decimalValue()).isEqualByComparingTo("25000.00");
        assertThat(get("/api/account/balance", custom.token()).json()
                .path("availableCash").decimalValue()).isEqualByComparingTo("25000.00");
        assertThat(get("/api/account/balance", defaults.token()).json()
                .path("initialBalance").decimalValue()).isEqualByComparingTo("100000.00");
        List<TransactionLedger> customGrants = ledgerOf(custom).stream()
                .filter(entry -> entry.getEntryType() == LedgerEntryType.INITIAL_GRANT)
                .toList();
        assertThat(customGrants).hasSize(1);
        assertThat(customGrants.get(0).getAmount()).isEqualByComparingTo("25000.00");
        Response login = post("/api/auth/login", null, Map.of(
                "identifier", custom.username(), "password", PASSWORD));
        assertThat(get("/api/account/balance", login.json().path("accessToken").asText()).json()
                .path("initialBalance").decimalValue()).isEqualByComparingTo("25000.00");

        String username = "invalid" + System.nanoTime() % 100000;
        Response invalid = post("/api/auth/register", null, Map.of(
                "email", username + "@tradex.test",
                "username", username,
                "fullName", "Invalid capital",
                "password", PASSWORD,
                "initialCapital", 99));
        assertThat(invalid.status()).isEqualTo(400);
    }

    @Test
    void confirmedResetCanChangeInitialCapitalWithoutAddingAnotherGrant() throws Exception {
        Session session = register();

        Response reset = post("/api/account/reset", session.token(),
                Map.of("confirm", true, "initialCapital", 5000));

        assertThat(reset.status()).isEqualTo(200);
        assertThat(reset.json().path("availableCash").decimalValue()).isEqualByComparingTo("5000.00");
        assertThat(get("/api/account/balance", session.token()).json()
                .path("initialBalance").decimalValue()).isEqualByComparingTo("5000.00");
        assertThat(ledgerOf(session).stream()
                .filter(entry -> entry.getEntryType() == LedgerEntryType.INITIAL_GRANT)).hasSize(1);
        assertThat(ledgerOf(session).stream()
                .filter(entry -> entry.getEntryType() == LedgerEntryType.ACCOUNT_RESET)).hasSize(1);
    }

    @Test
    void legacyInrAccountCannotPlaceUsdOrdersUsingNominalValuationRate() throws Exception {
        Session session = register();

        Response response = post("/api/orders", session.token(), Map.of(
                "symbol", "BTCUSD", "side", "BUY", "orderType", "MARKET", "quantity", 0.001));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().path("code").asText()).isEqualTo("CURRENCY_CONVERSION_UNAVAILABLE");
        assertThat(get("/api/account/balance", session.token()).json()
                .path("currency").asText()).isEqualTo("INR");
    }

    @Test
    void duplicateRegistrationIsRejectedAsAConflict() throws Exception {
        Session session = register();

        Response again = post("/api/auth/register", null, Map.of(
                "email", session.email(),
                "username", session.username(),
                "fullName", "Duplicate",
                "password", PASSWORD));

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.json().path("code").asText()).isNotBlank();
    }

    @Test
    void loginIssuesATokenForCorrectCredentialsAndRefusesWrongOnes() throws Exception {
        Session session = register();

        Response good = post("/api/auth/login", null, Map.of(
                "identifier", session.username(), "password", PASSWORD));
        assertThat(good.status()).isEqualTo(200);
        assertThat(good.json().path("accessToken").asText()).isNotBlank();

        // Email works as an identifier too.
        Response byEmail = post("/api/auth/login", null, Map.of(
                "identifier", session.email(), "password", PASSWORD));
        assertThat(byEmail.status()).isEqualTo(200);

        Response bad = post("/api/auth/login", null, Map.of(
                "identifier", session.username(), "password", "wrong-password"));
        assertThat(bad.status()).isEqualTo(401);
        assertThat(bad.json().path("code").asText()).isEqualTo("UNAUTHORIZED");

        JsonNode me = get("/api/auth/me", session.token()).json();
        assertThat(me.path("username").asText()).isEqualTo(session.username());
    }

    @Test
    void everyNewAccountStartsWithTheDefaultWatchlist() throws Exception {
        Session session = register();

        JsonNode watchlist = get("/api/watchlist", session.token()).json();
        JsonNode items = watchlist.path("items");
        assertThat(items.size()).isEqualTo(10);

        List<String> symbols = new java.util.ArrayList<>();
        items.forEach(item -> symbols.add(item.path("symbol").asText()));
        assertThat(symbols).containsExactlyInAnyOrder(
                "NIFTY50", "SENSEX", "RELIANCE", "TCS", "INFY", "HDFCBANK", "BTCINR", "ETHINR",
                "BTCUSD", "ETHUSD");
        assertThat(watchlist.path("dataSource").asText()).isEqualTo("SIMULATED");
    }

    @Test
    void resetRequiresExplicitConfirmationAndRestoresTheExactOpeningBalance() throws Exception {
        Session session = register();

        // Trade first so the reset has something to clear.
        assertThat(post("/api/orders", session.token(), Map.of(
                "symbol", "RELIANCE", "side", "BUY", "orderType", "MARKET", "quantity", 5))
                .status()).isEqualTo(201);
        assertThat(get("/api/portfolio/holdings", session.token()).json().size()).isEqualTo(1);

        // A bare reset must not wipe a portfolio.
        Response unconfirmed = post("/api/account/reset", session.token(), Map.of("confirm", false));
        assertThat(unconfirmed.status()).isEqualTo(400);
        assertThat(unconfirmed.json().path("code").asText()).isEqualTo("RESET_NOT_CONFIRMED");
        assertThat(get("/api/portfolio/holdings", session.token()).json().size()).isEqualTo(1);

        Response reset = post("/api/account/reset", session.token(), Map.of("confirm", true));
        assertThat(reset.status()).isEqualTo(200);
        assertThat(reset.json().path("availableCash").decimalValue()).isEqualByComparingTo("100000.00");
        assertThat(reset.json().path("resetCount").asInt()).isEqualTo(1);

        // The book is clean again.
        assertThat(get("/api/portfolio/holdings", session.token()).json().size()).isZero();
        assertThat(get("/api/orders", session.token()).json().path("totalElements").asLong()).isZero();
        assertThat(get("/api/trades", session.token()).json().path("totalElements").asLong()).isZero();
        assertThat(get("/api/account/balance", session.token()).json()
                .path("availableCash").decimalValue()).isEqualByComparingTo("100000.00");

        // The audit trail survives, and no second grant was ever written.
        List<TransactionLedger> ledger = ledgerOf(session);
        assertThat(ledger.stream().filter(entry -> entry.getEntryType() == LedgerEntryType.INITIAL_GRANT))
                .hasSize(1);
        assertThat(ledger.stream().filter(entry -> entry.getEntryType() == LedgerEntryType.ACCOUNT_RESET))
                .hasSize(1);

        // Resetting again is idempotent in cash terms: still exactly one lakh, never two.
        Response second = post("/api/account/reset", session.token(), Map.of("confirm", true));
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.json().path("resetCount").asInt()).isEqualTo(2);
        assertThat(get("/api/account/summary", session.token()).json()
                .path("availableCash").decimalValue()).isEqualByComparingTo("100000.00");
        assertThat(ledgerOf(session).stream()
                .filter(entry -> entry.getEntryType() == LedgerEntryType.INITIAL_GRANT))
                .hasSize(1);
    }
}

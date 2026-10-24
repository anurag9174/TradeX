package com.tradex.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradex.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * USD-quoted instruments (Delta Exchange perpetuals) inside a USD paper-trading account.
 *
 * <p>USD instruments are accounted in USD without implicit FX. Instruments requiring
 * cross-currency conversion are rejected until a reliable conversion source is configured.</p>
 */
@TestPropertySource(properties = {
        "tradex.trading.default-account-currency=USD",
        "tradex.trading.initial-balance=10000.00"
})
class UsdInstrumentTradingApiTest extends ApiTestSupport {

    private static final BigDecimal QTY = new BigDecimal("0.005");

    @Test
    void catalogueListsTheDeltaUsdPerpetuals() {
        assertThat(instrument("BTCUSD").getCurrency()).isEqualTo("USD");
        assertThat(instrument("BTCUSD").getExchange()).isEqualTo("DELTA");
        assertThat(instrument("BTCUSD").getProviderSymbol()).isEqualTo("DELTA:BTCUSD");
        assertThat(instrument("ETHUSD").getCurrency()).isEqualTo("USD");
        assertThat(instrument("BTCUSD").isTradable()).isTrue();
    }

    @Test
    void estimateUsesUsdForUsdQuotedAndSettledInstrument() throws Exception {
        Session session = register();

        Response estimate = post("/api/orders/estimate", session.token(), Map.of(
                "symbol", "BTCUSD", "side", "BUY", "orderType", "MARKET", "quantity", QTY));

        assertThat(estimate.status()).isEqualTo(200);
        JsonNode body = estimate.json();
        assertThat(body.path("priceCurrency").asText()).isEqualTo("USD");
        assertThat(body.path("ledgerCurrency").asText()).isEqualTo("USD");
        assertThat(body.path("fxRate").decimalValue()).isEqualByComparingTo("1");

        BigDecimal assumed = body.path("assumedExecutionPrice").decimalValue();
        BigDecimal expected = assumed.multiply(QTY);
        assertThat(body.path("orderValue").decimalValue().subtract(expected).abs())
                .isLessThan(new BigDecimal("0.01"));
    }

    @Test
    void marketBuySpendsLedgerCashAndCapitalisesChargesIntoTheUsdAverageCost() throws Exception {
        Session session = register();

        Response placed = post("/api/orders", session.token(), Map.of(
                "symbol", "BTCUSD", "side", "BUY", "orderType", "MARKET", "quantity", QTY));
        assertThat(placed.status()).as(placed.raw()).isEqualTo(201);
        JsonNode order = placed.order();
        assertThat(order.path("status").asText()).isEqualTo("EXECUTED");
        assertThat(order.path("currency").asText()).isEqualTo("USD");
        assertThat(order.path("accountCurrency").asText()).isEqualTo("USD");

        BigDecimal fill = order.path("avgFillPrice").decimalValue();
        BigDecimal executedValue = order.path("executedValue").decimalValue();
        BigDecimal charges = order.path("charges").decimalValue();
        BigDecimal expectedLedger = fill.multiply(QTY);
        assertThat(executedValue.subtract(expectedLedger).abs()).isLessThan(new BigDecimal("0.01"));

        JsonNode summary = get("/api/account/summary", session.token()).json();
        assertThat(summary.path("currency").asText()).isEqualTo("USD");
        BigDecimal cashSpent = new BigDecimal("10000.00")
                .subtract(summary.path("availableCash").decimalValue());
        assertThat(cashSpent.subtract(executedValue).subtract(charges).abs()).isLessThan(new BigDecimal("0.01"));
        assertThat(summary.path("fxNote").isNull()).isTrue();

        JsonNode positions = get("/api/portfolio/holdings", session.token()).json();
        assertThat(positions).hasSize(1);
        JsonNode position = positions.get(0);
        assertThat(position.path("symbol").asText()).isEqualTo("BTCUSD");
        assertThat(position.path("currency").asText()).isEqualTo("USD");
        // Average cost is a USD price: (turnover + charges) / quantity.
        BigDecimal expectedAvgCost = executedValue.add(charges)
                .divide(QTY, 6, RoundingMode.HALF_UP);
        assertThat(position.path("avgCost").decimalValue()).isEqualByComparingTo(expectedAvgCost);
        // Market value and price are both USD.
        BigDecimal marketValue = position.path("marketValue").decimalValue();
        BigDecimal price = position.path("currentPrice").decimalValue();
        assertThat(marketValue.subtract(price.multiply(QTY)).abs())
                .isLessThan(new BigDecimal("0.01"));
    }

    @Test
    void restingUsdLimitBuyReservesLedgerCashIncludingCharges() throws Exception {
        Session session = register();

        Response placed = post("/api/orders", session.token(), Map.of(
                "symbol", "BTCUSD", "side", "BUY", "orderType", "LIMIT",
                "quantity", QTY, "limitPrice", 50_000));
        assertThat(placed.status()).as(placed.raw()).isEqualTo(201);
        JsonNode order = placed.order();
        assertThat(order.path("status").asText()).isEqualTo("PENDING");

        BigDecimal expectedReservation = QTY.multiply(new BigDecimal("50000"));
        BigDecimal charges = order.path("estimatedCharges").decimalValue();
        assertThat(order.path("reservedCash").decimalValue()
                .subtract(expectedReservation.add(charges)).abs()).isLessThan(new BigDecimal("0.01"));

        JsonNode balance = get("/api/account/balance", session.token()).json();
        assertThat(balance.path("reservedCash").decimalValue())
                .isEqualByComparingTo(order.path("reservedCash").decimalValue());
    }

    @Test
    void oversizedUsdOrderIsRefusedInLedgerTerms() throws Exception {
        Session session = register();

        // A 1 BTC order at $82,000 exceeds the default $10,000 account.
        Response response = post("/api/orders", session.token(), Map.of(
                "symbol", "BTCUSD", "side", "BUY", "orderType", "LIMIT",
                "quantity", 1, "limitPrice", 82_000));

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().path("code").asText()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void realisedPnlFromARoundTripIsAccountCurrency() throws Exception {
        Session session = register();

        Response buy = post("/api/orders", session.token(), Map.of(
                "symbol", "BTCUSD", "side", "BUY", "orderType", "MARKET", "quantity", QTY));
        assertThat(buy.status()).as(buy.raw()).isEqualTo(201);

        Response sell = post("/api/orders", session.token(), Map.of(
                "symbol", "BTCUSD", "side", "SELL", "orderType", "MARKET", "quantity", QTY));
        assertThat(sell.status()).as(sell.raw()).isEqualTo(201);
        JsonNode sellOrder = sell.order();
        assertThat(sellOrder.path("status").asText()).isEqualTo("EXECUTED");

        JsonNode trades = get("/api/trades", session.token()).json();
        JsonNode newest = trades.path("content").get(0);
        assertThat(newest.path("symbol").asText()).isEqualTo("BTCUSD");
        assertThat(newest.path("currency").asText()).isEqualTo("USD");
        assertThat(newest.path("accountCurrency").asText()).isEqualTo("USD");

        BigDecimal sellFill = sellOrder.path("avgFillPrice").decimalValue();
        BigDecimal costBasis = newest.path("costBasis").decimalValue();
        BigDecimal sellCharges = sellOrder.path("charges").decimalValue();
        BigDecimal expectedRealized = sellFill.subtract(costBasis).multiply(QTY)
                .subtract(sellCharges);
        // costBasis is the USD average cost at 6dp; allow a cent for stored rounding.
        assertThat(newest.path("realizedPnl").decimalValue().subtract(expectedRealized).abs())
                .isLessThan(new BigDecimal("0.01"));

        // A round trip near the same USD price costs the simulated order charges.
        JsonNode summary = get("/api/account/summary", session.token()).json();
        BigDecimal loss = new BigDecimal("10000.00").subtract(summary.path("portfolioValue").decimalValue());
        assertThat(loss).isBetween(new BigDecimal("-2000"), new BigDecimal("2000"));
    }

    @Test
    void positionSizerInterpretsUsdPricesAndFloorsToTheFractionalLot() throws Exception {
        Session session = register();

        Response sized = post("/api/risk/position-size", session.token(), Map.of(
                "entryPrice", 82_000, "stopPrice", 81_000, "riskPercent", 1,
                "lotSize", 0.001, "symbol", "BTCUSD"));

        assertThat(sized.status()).isEqualTo(200);
        JsonNode body = sized.json();
        assertThat(body.path("priceCurrency").asText()).isEqualTo("USD");
        assertThat(body.path("ledgerCurrency").asText()).isEqualTo("USD");
        assertThat(body.path("fxRate").decimalValue()).isEqualByComparingTo("1");
        assertThat(body.path("riskPerUnit").decimalValue()).isEqualByComparingTo("1000");
        assertThat(body.path("capitalAtRisk").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(body.path("suggestedQuantity").decimalValue()).isEqualByComparingTo("0.1");
        assertThat(body.path("suggestedNotional").decimalValue()).isEqualByComparingTo("8200.00");
        assertThat(body.path("rationale").asText()).doesNotContain("nominal rate");

        // Without an instrument, the provided prices still use the account's USD currency.
        Response withoutSymbol = post("/api/risk/position-size", session.token(), Map.of(
                "entryPrice", 82_000, "stopPrice", 81_000, "riskPercent", 1, "lotSize", 0.001));
        assertThat(withoutSymbol.status()).isEqualTo(200);
        assertThat(withoutSymbol.json().path("suggestedQuantity").decimalValue())
                .isEqualByComparingTo("0.1");
        assertThat(withoutSymbol.json().path("priceCurrency").asText()).isEqualTo("USD");
    }

    @Test
    void riskSummaryReportsUsdWithoutInventingAnFxRate() throws Exception {
        Session session = register();

        JsonNode before = get("/api/risk/summary", session.token()).json();
        assertThat(before.path("fxNote").isNull()).isTrue();

        assertThat(post("/api/orders", session.token(), Map.of(
                "symbol", "BTCUSD", "side", "BUY", "orderType", "MARKET", "quantity", QTY))
                .status()).isEqualTo(201);

        JsonNode after = get("/api/risk/summary", session.token()).json();
        assertThat(after.path("fxNote").isNull()).isTrue();
        JsonNode openPosition = after.path("openPositions").get(0);
        assertThat(openPosition.path("currency").asText()).isEqualTo("USD");
    }

    @Test
    void usdAccountRejectsInrInstrumentUntilReliableConversionExists() throws Exception {
        Session session = register();

        Response response = post("/api/orders", session.token(), Map.of(
                "symbol", "RELIANCE", "side", "BUY", "orderType", "MARKET", "quantity", 1));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().path("code").asText()).isEqualTo("CURRENCY_CONVERSION_UNAVAILABLE");
    }
}

package com.tradex.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradex.entity.Order;
import com.tradex.entity.TransactionLedger;
import com.tradex.enums.LedgerEntryType;
import com.tradex.support.ApiTestSupport;
import com.tradex.trading.Decimals;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The complete order lifecycle through the public API: market fills, charge capitalisation,
 * realised P&amp;L, rejection paths, limit reservations, cancellation, stop-loss triggering and
 * idempotent replays.
 */
class OrderExecutionApiTest extends ApiTestSupport {

    // =======================================================================================
    // Market fills, average cost, realised P&L
    // =======================================================================================

    @Test
    void marketBuysCapitaliseChargesIntoAverageCostAndSellsRealiseAgainstIt() throws Exception {
        Session session = register();
        String token = session.token();

        // --- first buy ------------------------------------------------------------------
        JsonNode first = placedOrder(post("/api/orders", token, Map.of(
                "symbol", "RELIANCE", "side", "BUY", "orderType", "MARKET", "quantity", 10)));
        assertThat(first.path("status").asText()).isEqualTo("EXECUTED");
        assertThat(first.path("filledQuantity").decimalValue()).isEqualByComparingTo("10");

        BigDecimal price1 = first.path("avgFillPrice").decimalValue();
        BigDecimal charges1 = first.path("charges").decimalValue();
        BigDecimal turnover1 = Decimals.money(price1.multiply(BigDecimal.TEN, Decimals.MC));
        assertThat(price1).isGreaterThan(BigDecimal.ZERO);
        assertThat(charges1).isGreaterThan(BigDecimal.ZERO);

        // Cash moved by exactly turnover + charges; no slippage is configured so the fill is the quote.
        BigDecimal cashAfterBuy1 = new BigDecimal("100000.00").subtract(turnover1).subtract(charges1);
        assertMoney(get("/api/account/balance", token).json(), "availableCash", cashAfterBuy1);

        // Average cost includes buy charges: (qty*fill + charges) / qty.
        BigDecimal avgCost1 = turnover1.add(charges1)
                .divide(BigDecimal.TEN, Decimals.QTY_SCALE, RoundingMode.HALF_UP);
        assertMoney(holding(token, "RELIANCE"), "avgCost", avgCost1);
        assertMoney(holding(token, "RELIANCE"), "quantity", "10");

        // --- second buy re-weights the average -------------------------------------------
        JsonNode second = placedOrder(post("/api/orders", token, Map.of(
                "symbol", "RELIANCE", "side", "BUY", "orderType", "MARKET", "quantity", 10)));
        BigDecimal price2 = second.path("avgFillPrice").decimalValue();
        BigDecimal charges2 = second.path("charges").decimalValue();
        BigDecimal turnover2 = Decimals.money(price2.multiply(BigDecimal.TEN, Decimals.MC));

        BigDecimal avgCost2 = BigDecimal.TEN.multiply(avgCost1, Decimals.MC)
                .add(turnover2, Decimals.MC)
                .add(charges2, Decimals.MC)
                .divide(new BigDecimal("20"), Decimals.QTY_SCALE, RoundingMode.HALF_UP);
        assertMoney(holding(token, "RELIANCE"), "avgCost", avgCost2);

        // --- partial sell realises (fill - avgCost) * qty - sell charges ------------------
        JsonNode sell = placedOrder(post("/api/orders", token, Map.of(
                "symbol", "RELIANCE", "side", "SELL", "orderType", "MARKET", "quantity", 4)));
        assertThat(sell.path("status").asText()).isEqualTo("EXECUTED");

        BigDecimal sellPrice = sell.path("avgFillPrice").decimalValue();
        BigDecimal sellCharges = sell.path("charges").decimalValue();
        BigDecimal expectedRealized = Decimals.money(
                sellPrice.subtract(avgCost2).multiply(new BigDecimal("4"), Decimals.MC).subtract(sellCharges));
        assertMoney(sell, "realizedPnl", expectedRealized);

        // The position keeps its cost basis and loses only quantity (20 bought - 4 sold).
        assertMoney(holding(token, "RELIANCE"), "quantity", "16");
        assertMoney(holding(token, "RELIANCE"), "avgCost", avgCost2);

        // Account-level realised P&L agrees with the trade, and the charge meter adds up.
        JsonNode portfolio = get("/api/portfolio", token).json();
        assertMoney(portfolio, "realizedPnl", expectedRealized);
        assertMoney(get("/api/account", token).json(), "totalCharges",
                charges1.add(charges2).add(sellCharges));

        // Cash after the sell = cash after buy 2 + (sell turnover - sell charges).
        BigDecimal cashAfterBuy2 = cashAfterBuy1.subtract(turnover2).subtract(charges2);
        BigDecimal sellTurnover = Decimals.money(sellPrice.multiply(new BigDecimal("4"), Decimals.MC));
        assertMoney(get("/api/account/balance", token).json(), "availableCash",
                cashAfterBuy2.add(sellTurnover).subtract(sellCharges));

        // The accounting identity that everything else rests on:
        // portfolioValue == initialBalance + realisedPnl + unrealisedPnl
        BigDecimal identity = new BigDecimal("100000.00")
                .add(portfolio.path("realizedPnl").decimalValue())
                .add(portfolio.path("unrealizedPnl").decimalValue());
        assertThat(portfolio.path("portfolioValue").decimalValue().subtract(identity).abs())
                .as("portfolio identity: value == grant + realised + unrealised")
                .isLessThanOrEqualTo(new BigDecimal("0.01"));
    }

    @Test
    void marketBuyBeyondTheBalanceIsRejectedWithoutSpendingAnything() throws Exception {
        Session session = register();

        // Market orders reserve nothing at intake; the rejection happens inside the matched fill.
        JsonNode order = placedOrder(post("/api/orders", session.token(), Map.of(
                "symbol", "RELIANCE", "side", "BUY", "orderType", "MARKET", "quantity", 1_000_000)));

        assertThat(order.path("status").asText()).isEqualTo("REJECTED");
        assertThat(order.path("rejectReason").asText()).contains("Insufficient virtual balance");
        assertMoney(get("/api/account/balance", session.token()).json(), "availableCash", "100000.00");
        assertThat(get("/api/portfolio/holdings", session.token()).json()).isEmpty();
        // Nothing but the grant touched the ledger.
        assertThat(ledgerOf(session)).allMatch(entry -> entry.getEntryType() == LedgerEntryType.INITIAL_GRANT);
    }

    @Test
    void limitBuyBeyondTheBalanceIsRefusedAtIntakeWithUnprocessableEntity() throws Exception {
        Session session = register();

        Response response = post("/api/orders", session.token(), Map.of(
                "symbol", "RELIANCE", "side", "BUY", "orderType", "LIMIT",
                "quantity", 100_000, "limitPrice", 2000));

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().path("code").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        // Intake rejection leaves no trace: no order, no reservation.
        assertThat(get("/api/orders", session.token()).json().path("totalElements").asLong()).isZero();
        assertMoney(get("/api/account/balance", session.token()).json(), "availableCash", "100000.00");
    }

    @Test
    void sellingMoreThanHeldIsRefused() throws Exception {
        Session session = register();

        Response response = post("/api/orders", session.token(), Map.of(
                "symbol", "RELIANCE", "side", "SELL", "orderType", "MARKET", "quantity", 5));

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().path("code").asText()).isEqualTo("INSUFFICIENT_HOLDINGS");

        // Holding 5 and selling 6: same refusal, using the real position as the limit.
        assertThat(post("/api/orders", session.token(), Map.of(
                "symbol", "RELIANCE", "side", "BUY", "orderType", "MARKET", "quantity", 5)).status())
                .isEqualTo(201);
        Response tooMany = post("/api/orders", session.token(), Map.of(
                "symbol", "RELIANCE", "side", "SELL", "orderType", "MARKET", "quantity", 6));
        assertThat(tooMany.status()).isEqualTo(422);
        assertThat(tooMany.json().path("code").asText()).isEqualTo("INSUFFICIENT_HOLDINGS");
    }

    // =======================================================================================
    // Limit orders, reservations and cancellation
    // =======================================================================================

    @Test
    void aRestingLimitOrderReservesCashUntilItIsCancelled() throws Exception {
        Session session = register();
        String token = session.token();
        BigDecimal market = price("TCS");
        BigDecimal limit = market.multiply(new BigDecimal("0.80")).setScale(2, RoundingMode.HALF_UP);

        Response placed = post("/api/orders", token, Map.of(
                "symbol", "TCS", "side", "BUY", "orderType", "LIMIT",
                "quantity", 5, "limitPrice", limit));

        assertThat(placed.status()).isEqualTo(201);
        JsonNode order = placed.order();
        assertThat(order.path("status").asText()).isEqualTo("PENDING");
        BigDecimal reserved = order.path("reservedCash").decimalValue();
        assertThat(reserved).isGreaterThan(BigDecimal.ZERO);

        // The reservation is visible as a split of cash, not as a silent debit.
        JsonNode balance = get("/api/account/balance", token).json();
        assertMoney(balance, "reservedCash", reserved);
        assertMoney(balance, "availableCash", new BigDecimal("100000.00").subtract(reserved));
        assertThat(get("/api/orders/open", token).json()).hasSize(1);

        long orderId = order.path("id").asLong();
        Response cancelled = delete("/api/orders/" + orderId, token);
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.json().path("status").asText()).isEqualTo("CANCELLED");

        // Cancellation returns the cash to spendable and records the release.
        balance = get("/api/account/balance", token).json();
        assertMoney(balance, "availableCash", "100000.00");
        assertMoney(balance, "reservedCash", "0");
        assertThat(ledgerOf(session).stream()
                .filter(entry -> entry.getEntryType() == LedgerEntryType.CASH_RELEASED))
                .isNotEmpty();

        // A terminal order can never be cancelled twice.
        Response again = delete("/api/orders/" + orderId, token);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.json().path("code").asText()).isEqualTo("ORDER_NOT_CANCELLABLE");
    }

    @Test
    void aMarketableLimitOrderExecutesImmediatelyAtTheBetterMarketPrice() throws Exception {
        Session session = register();
        BigDecimal market = price("RELIANCE");
        BigDecimal generousLimit = market.multiply(new BigDecimal("1.20")).setScale(2, RoundingMode.HALF_UP);

        JsonNode order = placedOrder(post("/api/orders", session.token(), Map.of(
                "symbol", "RELIANCE", "side", "BUY", "orderType", "LIMIT",
                "quantity", 3, "limitPrice", generousLimit)));

        assertThat(order.path("status").asText()).isEqualTo("EXECUTED");
        // Price protection: never worse than the limit, and here at the market.
        assertThat(order.path("avgFillPrice").decimalValue()).isLessThanOrEqualTo(generousLimit);
        assertThat(holding(session.token(), "RELIANCE").path("quantity").decimalValue())
                .isEqualByComparingTo("3");
    }

    // =======================================================================================
    // Stop-loss triggering through the matching engine
    // =======================================================================================

    @Test
    void aRestingStopLossFiresOnceTheMarketTouchesIt() throws Exception {
        Session session = register();
        String token = session.token();

        // A stop can only be placed on the correct side of the market, so build a position first.
        assertThat(post("/api/orders", token, Map.of(
                "symbol", "INFY", "side", "BUY", "orderType", "MARKET", "quantity", 5)).status())
                .isEqualTo(201);

        BigDecimal market = price("INFY");
        BigDecimal stop = market.multiply(new BigDecimal("0.80")).setScale(2, RoundingMode.HALF_UP);
        JsonNode stopOrder = placedOrder(post("/api/orders", token, Map.of(
                "symbol", "INFY", "side", "SELL", "orderType", "STOP_LOSS",
                "quantity", 5, "stopPrice", stop)));
        assertThat(stopOrder.path("status").asText()).isEqualTo("PENDING");
        long orderId = stopOrder.path("id").asLong();

        // Simulate the crash the stop exists for: move the trigger above the market directly in the
        // database (a live feed would do this for us) and let the matching engine notice.
        Order persisted = orderRepository.findById(orderId).orElseThrow();
        persisted.setStopPrice(market.multiply(new BigDecimal("1.10")).setScale(2, RoundingMode.HALF_UP));
        orderRepository.saveAndFlush(persisted);

        matchingEngine.workRestingOrders();

        Order afterPass = orderRepository.findById(orderId).orElseThrow();
        assertThat(afterPass.getStatus().name()).isEqualTo("EXECUTED");
        assertThat(afterPass.getAvgFillPrice()).isNotNull();
        assertThat(afterPass.getExecutedAt()).isNotNull();
        assertThat(holdingRepository.findByAccountIdAndInstrumentId(session.accountId(), instrument("INFY").getId()))
                .isEmpty();

        // Exactly one trade, and a re-run of the engine cannot book a second one.
        assertThat(tradeRepository.findByOrderId(orderId)).hasSize(1);
        assertThat(executionService.attemptFill(orderId).isFilled()).isFalse();
        assertThat(tradeRepository.findByOrderId(orderId)).hasSize(1);
    }

    // =======================================================================================
    // Idempotency
    // =======================================================================================

    @Test
    void replayingAnIdempotencyKeyReturnsTheOriginalOrderAndExecutesOnce() throws Exception {
        Session session = register();
        String token = session.token();
        String key = "it-idem-" + System.nanoTime();

        JsonNode first = placedOrder(post("/api/orders", token, Map.of(
                "symbol", "RELIANCE", "side", "BUY", "orderType", "MARKET",
                "quantity", 3, "idempotencyKey", key)));
        assertThat(first.path("status").asText()).isEqualTo("EXECUTED");
        String orderRef = first.path("orderRef").asText();
        BigDecimal cashAfterFill = get("/api/account/balance", token).json().path("availableCash").decimalValue();

        Response replay = post("/api/orders", token, Map.of(
                "symbol", "RELIANCE", "side", "BUY", "orderType", "MARKET",
                "quantity", 3, "idempotencyKey", key));
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.order().path("orderRef").asText()).isEqualTo(orderRef);
        assertThat(replay.json().path("message").asText()).contains("Duplicate");

        assertMoney(get("/api/account/balance", token).json(), "availableCash", cashAfterFill);
        assertThat(tradeRepository.findByAccountIdOrderByExecutedAtDesc(session.accountId())).hasSize(1);

        // The Idempotency-Key header is an equivalent channel for the same guarantee.
        String headerKey = "it-header-" + System.nanoTime();
        MockHttpServletRequestBuilder withHeader = MockMvcRequestBuilders.post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header(AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", headerKey)
                .content(objectMapper.writeValueAsString(Map.of(
                        "symbol", "RELIANCE", "side", "BUY", "orderType", "MARKET", "quantity", 2)));
        Response headerFirst = call(withHeader);
        assertThat(headerFirst.status()).isEqualTo(201);
        Response headerReplay = call(MockMvcRequestBuilders.post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header(AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", headerKey)
                .content(objectMapper.writeValueAsString(Map.of(
                        "symbol", "RELIANCE", "side", "BUY", "orderType", "MARKET", "quantity", 2))));
        assertThat(headerReplay.order().path("orderRef").asText())
                .isEqualTo(headerFirst.order().path("orderRef").asText());
        assertThat(tradeRepository.findByAccountIdOrderByExecutedAtDesc(session.accountId())).hasSize(2);
    }

    // =======================================================================================
    // Helpers
    // =======================================================================================

    private JsonNode placedOrder(Response response) {
        assertThat(response.status()).as("place order: %s", response.raw()).isEqualTo(201);
        return response.order();
    }

    private JsonNode holding(String token, String symbol) throws Exception {
        JsonNode holdings = get("/api/portfolio/holdings", token).json();
        for (JsonNode holding : holdings) {
            if (symbol.equals(holding.path("symbol").asText())) {
                return holding;
            }
        }
        throw new AssertionError("No holding for " + symbol + " in " + holdings);
    }

    private static void assertMoney(JsonNode node, String field, BigDecimal expected) {
        assertThat(node.path(field).decimalValue())
                .as("field %s of %s", field, node)
                .isEqualByComparingTo(expected);
    }

    private static void assertMoney(JsonNode node, String field, String expected) {
        assertMoney(node, field, new BigDecimal(expected));
    }
}

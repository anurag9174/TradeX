package com.tradex.api;

import com.tradex.dto.order.OrderActionResponse;
import com.tradex.dto.order.PlaceOrderRequest;
import com.tradex.entity.TradingAccount;
import com.tradex.enums.OrderSide;
import com.tradex.enums.OrderStatus;
import com.tradex.enums.OrderType;
import com.tradex.exception.InsufficientFundsException;
import com.tradex.support.ApiTestSupport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency: the same rupee must never fund two fills, and the same order must never book two
 * trades - no matter how many threads try at once.
 *
 * <p>These tests drive the service layer directly from several threads: the account row lock in
 * {@code OrderService} and the order row lock in {@code OrderExecutionService} are the actual
 * subjects under test, and the HTTP layer adds nothing to that guarantee.</p>
 */
class ConcurrencyApiTest extends ApiTestSupport {

    @Test
    void twoMarketBuysCannotSpendTheSameCashTwice() throws Exception {
        Session session = register();
        BigDecimal quantity = new BigDecimal("50"); // ~64.5k per order at ~1290; two exceed 100k.

        List<Object> results = runConcurrently(
                () -> orderService.placeOrder(session.userId(), marketBuy(quantity)),
                () -> orderService.placeOrder(session.userId(), marketBuy(quantity)));

        List<OrderActionResponse> responses = responses(results);
        long executed = responses.stream()
                .filter(response -> response.order().status() == OrderStatus.EXECUTED).count();
        long rejected = responses.stream()
                .filter(response -> response.order().status() == OrderStatus.REJECTED).count();

        // Exactly one order may consume the cash; the second is cleanly rejected, not half-filled.
        assertThat(executed).as("executed among %s", responses).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);

        TradingAccount account = accountOf(session);
        assertThat(account.getCashBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(account.getReservedCash()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(tradeRepository.findByAccountIdOrderByExecutedAtDesc(session.accountId())).hasSize(1);
        assertThat(holdingRepository
                .findByAccountIdAndInstrumentId(session.accountId(), instrument("RELIANCE").getId()))
                .hasValueSatisfying(holding -> assertThat(holding.getQuantity()).isEqualByComparingTo("50"));
    }

    @Test
    void twoRestingReservationsCannotOverdrawTheAccount() throws Exception {
        Session session = register();
        BigDecimal limit = price("RELIANCE").multiply(new BigDecimal("0.95")).setScale(2, RoundingMode.HALF_UP);

        List<Object> results = runConcurrently(
                () -> orderService.placeOrder(session.userId(), restingBuy(limit, new BigDecimal("50"))),
                () -> orderService.placeOrder(session.userId(), restingBuy(limit, new BigDecimal("50"))));

        List<OrderActionResponse> responses = responses(results);
        List<InsufficientFundsException> refusals = new ArrayList<>();
        for (Object result : results) {
            if (result instanceof InsufficientFundsException refusal) {
                refusals.add(refusal);
            }
        }

        // One reservation may stand; the other must be refused before anything is written.
        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).order().status()).isEqualTo(OrderStatus.PENDING);
        assertThat(refusals).hasSize(1);

        BigDecimal reserved = responses.get(0).reservedCash();
        assertThat(reserved).isGreaterThan(BigDecimal.ZERO);
        TradingAccount account = accountOf(session);
        assertThat(account.getReservedCash()).isEqualByComparingTo(reserved);
        assertThat(account.getCashBalance())
                .isEqualByComparingTo(new BigDecimal("100000.00").subtract(reserved));
        assertThat(get("/api/orders/open", session.token()).json()).hasSize(1);
    }

    @Test
    void simultaneousFillAttemptsOnOneOrderBookExactlyOneTrade() throws Exception {
        Session session = register();
        String token = session.token();

        // A resting limit that is deliberately made fillable afterwards, so the threads race on a
        // genuinely PENDING order rather than on one already terminal.
        BigDecimal limit = price("RELIANCE").multiply(new BigDecimal("0.80")).setScale(2, RoundingMode.HALF_UP);
        long orderId = post("/api/orders", token, Map.of(
                "symbol", "RELIANCE", "side", "BUY", "orderType", "LIMIT",
                "quantity", 5, "limitPrice", limit)).order().path("id").asLong();

        var order = orderRepository.findById(orderId).orElseThrow();
        order.setLimitPrice(price("RELIANCE").multiply(new BigDecimal("1.20")).setScale(2, RoundingMode.HALF_UP));
        orderRepository.saveAndFlush(order);

        List<Object> results = runConcurrently(
                () -> executionService.attemptFill(orderId),
                () -> executionService.attemptFill(orderId),
                () -> executionService.attemptFill(orderId),
                () -> executionService.attemptFill(orderId));

        long filled = results.stream()
                .filter(result -> result instanceof com.tradex.trading.OrderExecutionService.Outcome outcome
                        && outcome.isFilled())
                .count();
        assertThat(filled).isEqualTo(1);
        assertThat(tradeRepository.findByOrderId(orderId)).hasSize(1);

        TradingAccount account = accountOf(session);
        assertThat(account.getReservedCash()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getCashBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    // =======================================================================================
    // Harness
    // =======================================================================================

    private static PlaceOrderRequest marketBuy(BigDecimal quantity) {
        return new PlaceOrderRequest("RELIANCE", OrderSide.BUY, OrderType.MARKET, quantity,
                null, null, null, null, null);
    }

    private static PlaceOrderRequest restingBuy(BigDecimal limit, BigDecimal quantity) {
        return new PlaceOrderRequest("RELIANCE", OrderSide.BUY, OrderType.LIMIT, quantity,
                limit, null, null, null, null);
    }

    private static List<OrderActionResponse> responses(List<Object> results) {
        return results.stream()
                .filter(OrderActionResponse.class::isInstance)
                .map(OrderActionResponse.class::cast)
                .toList();
    }

    @FunctionalInterface
    private interface Task {
        Object run() throws Exception;
    }

    /** Runs every task on its own thread, released simultaneously, collecting results/throwables. */
    private static List<Object> runConcurrently(Task... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Task task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.run();
                    } catch (Exception ex) {
                        return ex;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}

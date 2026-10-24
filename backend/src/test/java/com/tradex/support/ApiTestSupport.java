package com.tradex.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradex.config.TradingProperties;
import com.tradex.entity.Instrument;
import com.tradex.entity.TradingAccount;
import com.tradex.marketdata.MarketDataService;
import com.tradex.repository.HoldingRepository;
import com.tradex.repository.OrderRepository;
import com.tradex.repository.TradeRepository;
import com.tradex.repository.TradingAccountRepository;
import com.tradex.repository.TransactionLedgerRepository;
import com.tradex.repository.UserRepository;
import com.tradex.service.InstrumentService;
import com.tradex.trading.OrderExecutionService;
import com.tradex.trading.OrderMatchingEngine;
import com.tradex.trading.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared harness for the HTTP-level integration tests.
 *
 * <p>Everything runs against the full application context on H2-in-MySQL-mode, through the real
 * security filter chain (JWT), the real services and the real database - the same code paths a
 * running server executes. Each test gets its own freshly registered user, so accounts and data
 * never bleed between tests.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ApiTestSupport {

    protected static final String AUTHORIZATION = "Authorization";
    protected static final String PASSWORD = "Tradex!12345";

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected TradingAccountRepository accountRepository;

    @Autowired
    protected TransactionLedgerRepository ledgerRepository;

    @Autowired
    protected OrderRepository orderRepository;

    @Autowired
    protected TradeRepository tradeRepository;

    @Autowired
    protected HoldingRepository holdingRepository;

    @Autowired
    protected OrderService orderService;

    @Autowired
    protected OrderExecutionService executionService;

    @Autowired
    protected OrderMatchingEngine matchingEngine;

    @Autowired
    protected InstrumentService instrumentService;

    @Autowired
    protected MarketDataService marketDataService;

    @Autowired
    protected TradingProperties tradingProperties;

    // =======================================================================================
    // Sessions
    // =======================================================================================

    /** One registered user plus the addresses of their user/account rows. */
    protected record Session(String username, String email, Long userId, Long accountId, String token) {
    }

    protected Session register() throws Exception {
        return register("it");
    }

    /** Registers a unique user through the public API and returns their authenticated session. */
    protected Session register(String prefix) throws Exception {
        return register(prefix, null);
    }

    protected Session register(String prefix, BigDecimal initialCapital) throws Exception {
        String username = prefix + COUNTER.incrementAndGet() + System.nanoTime() % 100000;
        String email = username + "@tradex.test";

        Map<String, Object> request = new java.util.HashMap<>(Map.of(
                "email", email,
                "username", username,
                "fullName", "Integration " + prefix,
                "password", PASSWORD));
        if (initialCapital != null) {
            request.put("initialCapital", initialCapital);
        }
        Response response = post("/api/auth/register", null, request);

        assertThat(response.status()).as("register: %s", response.raw()).isEqualTo(201);
        String token = response.json().path("accessToken").asText();
        long userId = response.json().path("user").path("id").asLong();
        assertThat(token).isNotBlank();
        assertThat(userId).isPositive();

        TradingAccount account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new AssertionError("Registration did not open a trading account"));
        return new Session(username, email, userId, account.getId(), token);
    }

    // =======================================================================================
    // HTTP helpers
    // =======================================================================================

    /** Minimal decoded HTTP response so tests can assert on status, raw text and parsed JSON. */
    protected record Response(int status, String raw, JsonNode json) {
        public JsonNode order() {
            return json.path("order");
        }
    }

    protected Response call(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult result = mockMvc.perform(builder).andReturn();
        String raw = result.getResponse().getContentAsString();
        JsonNode json = raw == null || raw.isBlank()
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(raw);
        return new Response(result.getResponse().getStatus(), raw == null ? "" : raw, json);
    }

    protected Response get(String path, String token) throws Exception {
        return call(withAuth(MockMvcRequestBuilders.get(path), token));
    }

    protected Response post(String path, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            builder.content(objectMapper.writeValueAsString(body));
        }
        return call(withAuth(builder, token));
    }

    protected Response put(String path, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.put(path)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            builder.content(objectMapper.writeValueAsString(body));
        }
        return call(withAuth(builder, token));
    }

    protected Response delete(String path, String token) throws Exception {
        return call(withAuth(MockMvcRequestBuilders.delete(path), token));
    }

    private static MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder, String token) {
        if (token != null) {
            builder.header(AUTHORIZATION, "Bearer " + token);
        }
        return builder;
    }

    // =======================================================================================
    // Market + account conveniences
    // =======================================================================================

    protected Instrument instrument(String symbol) {
        return instrumentService.require(symbol);
    }

    /** The server-side execution price for a symbol, straight from the simulated feed. */
    protected BigDecimal price(String symbol) {
        BigDecimal price = marketDataService.executionPrice(instrument(symbol), null);
        assertThat(price).as("simulated price for %s", symbol).isNotNull();
        return price;
    }

    protected TradingAccount accountOf(Session session) {
        return accountRepository.findByUserId(session.userId())
                .orElseThrow(() -> new AssertionError("account disappeared"));
    }

    /** All ledger rows for an account's session, oldest first. */
    protected java.util.List<com.tradex.entity.TransactionLedger> ledgerOf(Session session) {
        return ledgerRepository.findByAccountIdOrderByCreatedAtAsc(session.accountId());
    }
}

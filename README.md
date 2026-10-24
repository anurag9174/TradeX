# TradeX — Virtual Trading Platform

TradeX is a **fully simulated (paper) trading platform**. New accounts use USD as their base
currency and can choose starting virtual capital from $100 to $1,000,000 ($10,000 by default).
Practice against a generated market feed, with an order book, matching engine and risk console.

> **Not real trading. Not real prices. No money can ever move.**
> - The platform never connects to a brokerage and has no order-routing code.
> - Prices come from a deterministic synthetic feed and are labelled **SIMULATED** on every quote,
>   candle, tick and page that displays them. The label is served by the backend, not hard-coded in
>   the UI.
> - A live provider (Twelve Data for equities/crypto, or Delta Exchange India for crypto
>   perpetuals) can be configured by an operator for *read-only* quotes; TradeX still never places a
>   real order.

---

## Contents

- [Feature tour](#feature-tour)
- [Tech stack](#tech-stack)
- [Repository layout](#repository-layout)
- [Quick start](#quick-start)
- [Tests](#tests)
- [How the money maths works](#how-the-money-maths-works)
- [Market data: modes and honesty rules](#market-data-modes-and-honesty-rules)
- [Risk management](#risk-management)
- [Security](#security)
- [Configuration](#configuration)
- [API](#api)
- [Limitations and non-goals](#limitations-and-non-goals)
- [Troubleshooting](#troubleshooting)

---

## Feature tour

| Area | What you get |
| --- | --- |
| **Dashboard** | Per-user starting capital, available cash, total account value, invested cost basis, unrealised/realised P&L, today's P&L and watchlist quotes |
| **Charts** | Candlestick / line / area with 8 timeframes (1m, 5m, 15m, 30m, 1h, 4h, 1d, 1w), volume, crosshair, zoom and pan (TradingView Lightweight Charts) |
| **Indicators** | SMA, EMA, RSI, MACD and Bollinger Bands — every one computed server-side **from the same candle series the chart draws**, toggleable per overlay/sub-pane |
| **Watchlist** | Per-user watchlist seeded with NIFTY50, SENSEX, RELIANCE, TCS, INFY, HDFCBANK, BTCINR, ETHINR, BTCUSD and ETHUSD (Delta Exchange perpetuals); add/remove any instrument from the catalogue |
| **Orders** | Market, limit and stop-loss orders, buy/sell, with server-side validation, pre-trade estimate (value + charges + affordability), confirm dialog, and a client-generated idempotency key on every submission; fractional quantities for crypto instruments |
| **Orders page** | Five tabs (all / open / executed / cancelled / rejected), filters, cancellation of resting orders that releases reserved cash |
| **Portfolio** | Valued holdings (average cost, market value, unrealised P&L, allocation, attached stop/target), equity curve, performance statistics |
| **Trades** | Append-only trade history with symbol/side/date/result filters, CSV export (RFC-4180) |
| **Risk console** | Position-size calculator (entry, stop, target, risk %, R:R), exposure overview, open-position risk lines, daily P&L, trading journal, configurable daily-loss limit that blocks new risk |
| **UX** | Dark professional theme, loading / empty / error states everywhere, toasts, confirm dialogs, keyboard-usable forms, direction conveyed by sign + arrow + label (never colour alone) |

---

## Tech stack

| Layer | Choice |
| --- | --- |
| Frontend | React 18 + Vite 5 + Tailwind CSS 3, TradingView Lightweight Charts 4.2, Axios, React Router 6, Context API, Lucide icons |
| Backend | Java 21+, Spring Boot 3.5.3 (Web, JPA, Security, Validation, WebSocket), Maven |
| Database | H2 in-memory database by default for local development; MySQL 8.4 with Hibernate + Flyway for persistent development/deployment; H2 in MySQL mode for tests |
| Auth | JWT (HS384), BCrypt password hashing, stateless sessions, per-IP rate limiting |
| Realtime | STOMP over SockJS on `/ws`, topics `/topic/quotes` and `/topic/account/{id}` |

---

## Repository layout

```
.
├── docker-compose.yml          # optional MySQL 8.4 on port 3307 (never collides with local 3306)
├── .env.example                # documented local and MySQL environment settings
├── scripts/smoke-test.sh       # end-to-end curl smoke test against a running backend
├── docs/API.md                 # endpoint-by-endpoint API reference
├── backend/
│   └── src/main/java/com/tradex/
│       ├── config/             # properties, security, websocket
│       ├── controller/         # REST controllers (one per resource)
│       ├── dto/                # API request/response models
│       ├── entity/             # JPA entities
│       ├── enums/              # order side/type/status, asset class, ledger entry types...
│       ├── exception/          # typed exceptions + one JSON error envelope
│       ├── marketdata/         # provider registry, simulated feed, Twelve Data / Delta providers,
│       │                       #   candles, indicators
│       ├── repository/         # Spring Data repositories (locking queries included)
│       ├── security/           # JWT filter/service, current-user, rate limit
│       ├── service/            # auth, account, instruments, risk, trades, watchlist
│       ├── trading/            # order intake, execution engine, matching engine, ledger,
│       │                       #   holdings, portfolio, charges, clock
│       └── websocket/          # quote broadcaster + per-account event stream
│   ├── src/main/resources/                 # application configuration and Flyway migrations
│   └── src/test/                           # unit and integration tests (H2, no Docker needed)
└── frontend/
    ├── package.json            # npm scripts and frontend dependencies
    └── src/
        ├── api/                # typed API clients, axios instance with 401 handling
        ├── components/         # ui primitives, chart, orders, portfolio widgets
        ├── context/            # Auth, Toast, Market (shared STOMP and Delta clients)
        ├── lib/                # formatting and chart-data helpers
        ├── pages/              # Login, Register, Dashboard, Markets, Watchlist, Orders,
        │                       #   Portfolio, Trades, Risk, protected route handling
        └── test/               # Vitest + RTL tests
```

---

## Quick start

**Prerequisites:** Java 21+, Maven 3.9+, Node.js 20+ and npm. Docker is optional; it is only
needed if you want to use the MySQL configuration instead of the default in-memory H2 database.

### Run locally with H2

The backend can use an in-memory H2 database on port **8081** without a separate database service.
The database is temporary: its data is lost when the backend stops. Enable Flyway so the database
schema and instrument catalogue are initialized from the project migrations.

```bash
# 1) Backend — Spring Boot on http://localhost:8081
cd backend
TRADEX_FLYWAY_ENABLED=true TRADEX_JPA_DDL_AUTO=none mvn spring-boot:run
```

In another terminal, start the frontend and point it at the backend:

```bash
# From the project root, in the frontend terminal
cd frontend
npm install
printf 'VITE_API_BASE_URL=http://localhost:8081\nVITE_WS_BASE_URL=http://localhost:8081\n' > .env.local
npm run dev
```

Open <http://localhost:5173> and register an account. Choose a starting capital from $100 to
$1,000,000, or use the documented $10,000 default when the API field is omitted. Capital can
only be changed through the separately confirmed account-reset action, which clears positions,
orders and trade history. Existing accounts are retained in their original INR currency until the
owner explicitly resets them; reset starts a new session in the configured default currency.
Swagger UI is at <http://localhost:8081/swagger-ui.html>, OpenAPI JSON at
<http://localhost:8081/api-docs>, and the health check at
<http://localhost:8081/actuator/health>.

Vite reads `.env.local` when it starts. The API and WebSocket base URLs must point to the port
configured for the backend (`SERVER_PORT`, default `8081`). Spring Boot does not automatically load
the root `.env` file; export backend environment variables in your shell before starting Maven.

### Run with MySQL for persistent data

Docker Compose starts a project-specific MySQL 8.4 instance on port 3307. The database volume
persists data across container restarts.

```bash
# From the project root
docker compose up -d mysql
docker compose ps

# In the backend terminal, configure MySQL and enable the Flyway migrations
cd backend
export TRADEX_DB_URL='jdbc:mysql://localhost:3307/tradex?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8&rewriteBatchedStatements=true'
export TRADEX_DB_USERNAME=tradex
export TRADEX_DB_PASSWORD=tradex
export TRADEX_DB_DRIVER_CLASS_NAME=com.mysql.cj.jdbc.Driver
export TRADEX_FLYWAY_ENABLED=true
export TRADEX_JPA_DDL_AUTO=validate
mvn spring-boot:run
```

For non-local deployments, use unique database credentials and a strong
`TRADEX_SECURITY_JWT_SECRET`; do not use the sample development secrets.

### Optional: read-only live market data

TradeX never sends orders to an exchange. For live public Delta Exchange quotes, start the backend
with the following environment variables; Twelve Data requires its own API key:

```bash
cd backend
TRADEX_FLYWAY_ENABLED=true TRADEX_JPA_DDL_AUTO=none \
  TRADEX_MARKETDATA_PROVIDER=delta TRADEX_MARKETDATA_MODE=live mvn spring-boot:run
```

See [Configuration](#configuration) and [Market data](#market-data-modes-and-honesty-rules) for
available settings and provider coverage.

---

## Tests

### Backend — `mvn test` (no Docker required)

```bash
cd backend
mvn test
```

The backend suite runs against the Spring application context and an in-memory H2 database in
MySQL compatibility mode. Tests enable Flyway and apply the database migrations, without requiring
Docker or a running MySQL server.

| Suite | Covers |
| --- | --- |
| `ChargesCalculatorTest` | brokerage cap, GST base, stamp duty (buy-only), DP charge (sell-only), paise rounding |
| `CurrencyConverterTest` | nominal USD→INR conversion: factor lookup, 4-dp money rounding, `null` note when everything already is INR |
| `SimulatedPriceModelTest` | feed determinism, per-instrument decorrelation, tick alignment, volume |
| `MarketDataRegistryTest` | provider selection and fail-fast: unknown provider refuses to start, `auto` + Delta is live with no key, `live` requires a configured provider |
| `DeltaExchangeMarketDataProviderTest` | Delta ticker mapping (LTP, previous close from `ltp_change_24h`), candle DESC→ASC ordering and trimming, resolution mapping, unavailable symbols report themselves unavailable (local HTTP stub) |
| `CandleServiceLiveTest` | live candles come from the provider's own bars only, closed live bars persist with `source=LIVE`, the fallback path never synthesises rows |
| `IndicatorCalculatorTest` | SMA/EMA warm-up and exactness, RSI bounds and extremes, MACD, Bollinger |
| `MarketDataApiTest` | indicator responses keyed by the exact requested spec (`macd:12-26-9`), index alignment with the shared time axis, 2σ Bollinger bands straddle the middle line, MACD histogram = line − signal, unknown specs rejected with 400 |
| `AccountLifecycleApiTest` | legacy INR account capital, duplicate registration, login, default watchlist, and confirmed reset semantics (incl. no double grant) |
| `UsdCapitalApiTest` | USD capital lower/upper bounds, $10,000 default, persistence across login, and per-user isolation |
| `OrderExecutionApiTest` | market fills, charge capitalisation into average cost, realised P&L, insufficient funds/holdings (422), resting limit + reservation + cancellation, marketable limit, stop-loss trigger via the matching engine, idempotency keys |
| `UsdInstrumentTradingApiTest` | USD account order accounting for BTCUSD, correct USD risk/P&L, and rejection of unsupported INR instruments |
| `RiskGuardApiTest` | daily-loss limit blocks new risk (403) while protective exits still fire, breach clearing, stop and target handling |
| `SecurityIsolationApiTest` | 401 for anonymous/garbage tokens, cross-user invisibility (404), tampered-token rejection, public market reads |
| `ConcurrencyApiTest` | two simultaneous market buys can only spend the cash once, two reservations cannot overdraw, four simultaneous fill attempts book exactly one trade |

### Frontend — `npm test`

```bash
cd frontend
npm test          # Vitest + Testing Library, jsdom
```

Covers the order-entry flow (estimate → confirm → submit with idempotency key, cash-estimate
refresh after cancellations), portfolio rendering, and the formatting helpers. `npm run build`
produces the production bundle.

### End-to-end smoke test

With the backend running:

```bash
BASE=http://localhost:8081 ./scripts/smoke-test.sh
```

Registers a user, checks the grant, places and cancels orders, replays an idempotency key, verifies
rejections, exports CSV, exercises the risk endpoints and proves two users cannot see each other's
data — all via curl.

---

## How the money maths works

- All money is `BigDecimal` (`DECIMAL(19,4)` in the database). No floating point anywhere near a
  balance.
- **Buy**: `newAvgCost = (qty*avgCost + turnover + buyCharges) / newQty`. Buy charges are
  **capitalised** into the position, so an immediate round trip shows a loss equal to the cost of
  dealing — the honest number.
- **Sell**: realises `(fillPrice − avgCost) × qty − sellCharges` and never changes the remaining
  cost basis. A position that hits zero is deleted.
- **Identity** (asserted in tests):
  `portfolioValue == initialBalance + realisedPnl + unrealisedPnl`.
- **Reservations**: a resting buy order moves `qty*price + estimatedCharges` from spendable cash to
  `reservedCash`. Cancellation/rejection releases it in full; execution consumes it. The same rupee
  can therefore never fund two open orders.
- **Ledger**: every balance mutation writes an append-only `transaction_ledger` row; the account
  balance is never edited without one. Resets add an `ACCOUNT_RESET` entry rather than rewriting
  history, and the one-time `INITIAL_GRANT` is written exactly once per account.

### Concurrency and duplicate execution

`OrderService` takes a pessimistic lock on the account row for every placement. The execution
engine locks the order row first, then the account, so all paths serialise in the same order. Fills
are additionally protected by (1) a status check on the locked order, (2) `orders.version`
optimistic locking, and (3) a unique `trades.execution_key`. `ConcurrencyApiTest` proves each of
these with real threads.

### Matching engine

A scheduled pass (every 2 s in dev) re-evaluates resting limit and stop-loss orders against the
current price, and — when enabled — converts a breached stop/target attached to a position into a
market exit (de-duplicated with a time-bucketed idempotency key). Protective exits are permitted to
fire even when the daily-loss guard has blocked new risk.

---

## Market data: modes and honesty rules

| Mode | Behaviour |
| --- | --- |
| `auto` (default) | uses the selected live provider when it is configured, otherwise the simulated feed |
| `simulated` | always the generated feed |
| `live` | requires a configured provider (Twelve Data needs an API key; Delta needs none); **the app refuses to start otherwise** and never silently substitutes simulated prices |

The simulated feed is a pure function of `(instrument, absolute time)`: the same second produces
the same price on every machine and across restarts, which is what makes P&L reproducible and the
engine deterministic under test. It is labelled `SIMULATED` end to end.

For supported Delta instruments, the frontend shares one public WebSocket connection and
subscribes to ticker and trade events for the selected symbol. Ticks update the active candle
incrementally; trades contribute OHLCV only when exchange price, size and timestamp are present.
Live candle buckets and the countdown use the latest historical candle's exchange timestamp as
their alignment anchor, falling back to epoch-aligned intervals until history is available.
Delta's numeric microsecond timestamps are normalized to chart seconds while retaining millisecond
precision for event ordering and quote freshness.
The chart requests 500 historical candles only when the symbol or timeframe changes. If the
Delta socket is disconnected, a guarded five-second REST fallback requests the current quote and
up to ten recent candles rather than reloading the full chart history. The chart shows the
market-data freshness status and a per-second candle close/next-open countdown in the user's local
timezone. The countdown is derived from candle boundaries, not from the arrival of WebSocket ticks.

Set `VITE_DELTA_WS_URL` in `frontend/.env.local` only when using a different Delta WebSocket
endpoint. It defaults to `wss://socket.india.delta.exchange`; the backend still controls whether
Delta live data is enabled, and a connected socket alone is not treated as proof of fresh data.

Indices (NIFTY50, SENSEX, BANKNIFTY) are chart-and-analysis only. They are not tradable and are
never assumed to be covered by a live provider — an operator can opt specific symbols in through
`tradex.marketdata.symbol-overrides`.

### Providers

| Provider (`TRADEX_MARKETDATA_PROVIDER`) | What it covers | Key required |
| --- | --- | --- |
| `twelvedata` (default) | NSE/BSE equities, crypto, any symbol an operator maps in | `TRADEX_MARKETDATA_API_KEY` |
| `delta` (alias `delta-exchange`) | Delta Exchange India crypto **derivatives** — perpetuals such as BTCUSD and ETHUSD | none — public market data |

Delta is a derivatives venue: no spot, no INR pairs, no NSE/BSE coverage. TradeX therefore ships
BTCUSD and ETHUSD as their own instruments (venue `DELTA`, currency `USD`) and **never** maps
`BTC/INR` onto a Delta dollar perpetual. In live mode an instrument the provider cannot serve shows
an explicit "live price unavailable" state — never a fabricated number. Live charts draw the
provider's own candles, including its currently-forming bar. If the socket is unavailable, only
fresh provider quotes and recent provider candles are eligible for fallback updates. A quote may
adjust the current candle's price range, but does not invent volume or missing candles.

A Delta venue example — live crypto charts and quotes with the paper ledger untouched:

```bash
cd backend
TRADEX_MARKETDATA_PROVIDER=delta TRADEX_MARKETDATA_MODE=live mvn spring-boot:run
```

### Account and instrument currencies

New accounts use **USD** and the Delta BTCUSD/ETHUSD perpetuals settle in USD, so their paper
orders, cash, costs, P&L and risk are all dimensionally USD. Instruments are traded only when
their quote and settlement currency match the account currency. TradeX has no live FX source and
will reject an order that would require currency conversion; it never fabricates an exchange rate.
Pre-existing accounts remain INR with their existing ledger and history until the user explicitly
confirms a reset. Reset clears positions, working orders and trades and starts a new account session
in the configured default currency. Historical INR valuations may use the legacy operator-set
nominal simulation rate, which is not a live FX feed.

---

## Risk management

- **Position-size calculator**: entry, stop, optional target and risk % → suggested quantity, capital
  at risk, R:R, and how much of the portfolio the position consumes.
- **Attached stop/target**: market buys can carry `positionStopLoss` / `positionTarget`; the engine
  watches them and exits the position when breached.
- **Daily-loss limit**: set an amount and enable it in the Risk console. Breaching it (realised +
  open losses for the *trading day*, `Asia/Kolkata` by default) blocks new risk-increasing orders
  with `403 TRADING_BLOCKED` until you clear the breach, reset the account, or the next trading day
  starts. Protective exits keep working.
- **Journal**: dated notes with optional symbol, tag, risk and reward amounts.
- **Exposure**: invested value, largest position, capital at risk per open position.

---

## Security

- BCrypt (strength 12) password hashing; passwords never leave the server.
- Stateless JWT (HS384), secret from `TRADEX_SECURITY_JWT_SECRET` (min 32 chars, startup fails
  otherwise), configurable expiry.
- Every account-scoped query is filtered by the authenticated principal; another user's resources
  answer `404`, not `403`, so IDs cannot be probed. Verified by `SecurityIsolationApiTest`.
- Per-IP rate limiting on all endpoints, with a stricter bucket on `/api/auth/*`.
- CORS allow-list from `TRADEX_ALLOWED_ORIGINS` (explicit origins, never `*` with credentials).
- One consistent JSON error envelope: `{timestamp, status, code, message, path, fieldErrors?}`.

The API **never accepts** price, charges, balance or P&L from the client — a tampered request can
only ask for something, never assert an outcome.

---

## Configuration

Backend settings are defined in `backend/src/main/resources/application.yml`; `.env.example`
documents commonly used environment variables. Spring Boot does not automatically read the root
`.env` file when launched with Maven, so export values in the shell or use the assignments in the
run instructions.

The checked-in defaults are an in-memory H2 datasource, `ddl-auto=update`, Flyway disabled, and
HTTP port `8081`. For a fully initialized local H2 database, enable Flyway and set
`TRADEX_JPA_DDL_AUTO=none` as shown in [Run locally with H2](#run-locally-with-h2). For persistent
MySQL, set the database URL, credentials and MySQL driver, enable Flyway, and set
`TRADEX_JPA_DDL_AUTO=validate` as shown in [Run with MySQL](#run-with-mysql-for-persistent-data).

Frontend build-time variables: `VITE_API_BASE_URL` and `VITE_WS_BASE_URL`. Set both to the running
backend URL; the checked-in backend configuration listens on port `8081`.

Database and migration settings:

| Variable | Default | Purpose |
| --- | --- | --- |
| `TRADEX_DB_URL` | In-memory H2 URL | JDBC URL; use the MySQL URL from `.env.example` for persistent storage |
| `TRADEX_DB_USERNAME` / `TRADEX_DB_PASSWORD` | `sa` / empty | Database credentials |
| `TRADEX_DB_DRIVER_CLASS_NAME` | `org.h2.Driver` | JDBC driver; set to `com.mysql.cj.jdbc.Driver` when overriding the datasource to MySQL |
| `TRADEX_FLYWAY_ENABLED` | `false` | Enable the versioned schema migrations (enable for MySQL) |
| `TRADEX_JPA_DDL_AUTO` | `update` | Hibernate schema behavior; use `validate` with Flyway-managed MySQL |
| `SERVER_PORT` | `8081` | Backend HTTP port |

Security and account settings:

| Variable | Default | Purpose |
| --- | --- | --- |
| `TRADEX_SECURITY_JWT_SECRET` | development-only secret | JWT signing key; set a strong secret of at least 32 characters outside local development |
| `TRADEX_ALLOWED_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` | Comma-separated CORS allow-list |
| `TRADEX_INITIAL_BALANCE` | `10000.00` | Default starting virtual capital if registration omits `initialCapital` |
| `TRADEX_MINIMUM_INITIAL_BALANCE` | `100.00` | Minimum selectable starting capital for registration and confirmed reset |
| `TRADEX_MAXIMUM_INITIAL_BALANCE` | `1000000.00` | Maximum selectable starting capital for registration and confirmed reset |
| `TRADEX_DEFAULT_ACCOUNT_CURRENCY` | `USD` | Currency assigned to new accounts and accounts after an explicit reset |
| `TRADEX_SEED_DEMO_USER` | `false` | Create a demo user at startup when enabled |
| `TRADEX_DEMO_USERNAME` / `TRADEX_DEMO_PASSWORD` / `TRADEX_DEMO_EMAIL` | `demo` / `demo12345` / `demo@tradex.local` | Demo account details; use only for local development |

Market-data settings:

| Variable | Default | Purpose |
| --- | --- | --- |
| `TRADEX_MARKETDATA_MODE` | `auto` | `auto`, `simulated`, or `live` |
| `TRADEX_MARKETDATA_PROVIDER` | `twelvedata` | Select `twelvedata` or `delta` |
| `TRADEX_MARKETDATA_API_KEY` | empty | Twelve Data API key |
| `TRADEX_MARKETDATA_BASE_URL` | `https://api.twelvedata.com` | Twelve Data API base URL |
| `TRADEX_DELTA_BASE_URL` | `https://api.india.delta.exchange` | Delta public API base URL |
| `TRADEX_FX_USD_INR` | `88.50` | Legacy INR-account valuation rate for historical USD positions; not a live FX feed and not used to enable cross-currency orders |

Trading and frontend settings:

| Variable | Default | Purpose |
| --- | --- | --- |
| `TRADEX_TRADING_ZONE` | `Asia/Kolkata` | Time zone for daily P&L and loss-limit boundaries |
| `TRADEX_SLIPPAGE_BPS` | `0` | Simulated market-order slippage in basis points |
| `TRADEX_MATCHING_ENABLED` | `true` | Enable scheduled matching for resting orders and attached levels |
| `VITE_API_BASE_URL` | Set to the backend URL | Frontend REST API base URL |
| `VITE_WS_BASE_URL` | Set to the backend URL | Frontend WebSocket/STOMP base URL |

---

## API

The full reference — parameters, payloads, status codes and `curl` examples — is in
[docs/API.md](docs/API.md). In short, `/api/auth/register` and `/api/auth/login` handle identity;
market catalogue, quote, candle and indicator reads are public; account, trading and risk
resources require a bearer token.

```
POST /api/auth/register|login            GET /api/auth/me
GET  /api/account | /balance | /summary  POST /api/account/reset
GET  /api/market/instruments | /quote/{symbol} | /candles/{symbol} | /indicators/{symbol} | /status
POST /api/orders | /orders/estimate      GET /api/orders[?tab=...] | /orders/open | /orders/{id}
DELETE /api/orders/{id}
GET  /api/portfolio | /holdings | /performance
GET  /api/trades[?filters] | /trades/export (CSV) | /trades/{id}
GET  /api/risk/summary | /open-positions  POST /api/risk/position-size  PUT /api/risk/settings
GET/POST /api/risk/journal               DELETE /api/risk/journal/{id}
GET/POST/DELETE /api/watchlist           GET /api/watchlist/catalogue
```

---

## Limitations and non-goals

- **Simulated venue only.** There is no brokerage integration, no order routing, and there never
  will be. Simulated prices must never be presented as real market data; the UI surfaces the
  server-provided `SIMULATED` badge everywhere.
- **Partial fills are not implemented.** Resting orders fill in full when their condition is met
  (`PARTIALLY_FILLED` exists in the schema for future use). Quantities are still normalised to lot
  sizes.
- **The simulated market is always open.** Exchange-hour sessions are deliberately not modelled
  (`tradex.trading.always-open`), and there are no corporate actions, splits, dividends, margins,
  short selling or derivatives.
- **Charges are a realistic model, not a tariff guarantee.** Brokerage is capped the same way
  discount brokers cap it, GST applies to the statutory base, and DP charges are per sell — but the
  numbers are configuration, not tax advice.
- **Live mode is read-only.** With Twelve Data or Delta Exchange configured, quotes and candles are
  fetched from the provider (subject to its rate limits and symbol coverage). The paper account then
  behaves exactly as in simulated mode; no real order ever leaves the machine.
- **Delta instruments are USD perpetuals.** Crypto only — no spot, no INR pairs, and no leverage,
  funding or margin simulation. A paper BTCUSD position is a plain spot-style holding whose cash
  side is converted at the nominal configured FX rate.
- **JWT logout is client-side.** Tokens are stateless and expire; there is no denylist.
- **Single-region timekeeping.** Daily boundaries use one configurable zone (`Asia/Kolkata`).

---

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| Backend exits with a JWT-secret error | Set `TRADEX_SECURITY_JWT_SECRET` to at least 32 characters |
| `Communications link failure` on backend start | The app may be configured for MySQL. Start the container with `docker compose up -d mysql`, check `docker compose ps`, and verify `TRADEX_DB_URL` and credentials |
| Frontend cannot reach the backend | Confirm the backend is running, then set `VITE_API_BASE_URL` and `VITE_WS_BASE_URL` in `frontend/.env.local` to the backend URL (default backend port: `8081`) and restart Vite |
| Data disappears after restarting the backend | The default H2 database is in-memory. Use the MySQL setup above for persistent local storage |
| Port 3306 already in use / unexpected DB state | The Compose MySQL service uses **3307**; check `docker compose ps` |
| Frontend shows "unauthorized" after leaving it open | Tokens expire (`TRADEX_SECURITY_JWT_EXPIRY_MINUTES`, default 12 h); log in again |
| Live mode refuses to start | `live` needs a configured provider — Twelve Data: `TRADEX_MARKETDATA_API_KEY`; Delta: nothing extra. Or go back to `auto`/`simulated` |
| `mvn test` needs no database | Correct — tests run on in-memory H2 in MySQL mode |

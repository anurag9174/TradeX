# TradeX API Reference

Base URL (local): `http://localhost:8080`. Interactive docs: `/swagger-ui.html` (OpenAPI at
`/api-docs`).

Everything here is a **simulation**. There is no endpoint that can move real money, and market
payloads carry `dataSource: "SIMULATED"` or `"LIVE"` plus a human-readable `dataSourceLabel` —
whichever the operator configured. Prices are never relabelled: an instrument the live provider
cannot serve answers with an explicit unavailable state instead of a number.

- [Conventions](#conventions)
- [Auth](#auth)
- [Account](#account)
- [Market data (public)](#market-data-public)
- [Orders](#orders)
- [Portfolio](#portfolio)
- [Trades](#trades)
- [Risk](#risk)
- [Watchlist](#watchlist)

## Conventions

**Authentication.** All endpoints except `/api/auth/register`, `/api/auth/login` and the public
market reads require the header:

```
Authorization: Bearer <accessToken>
```

**Error envelope.** Every failure returns the same JSON shape:

```json
{
  "timestamp": "2026-10-15T09:30:00Z",
  "status": 422,
  "code": "INSUFFICIENT_FUNDS",
  "message": "Insufficient virtual balance: required 128500.0000 but only 99370.5000 is available",
  "path": "/api/orders",
  "fieldErrors": { "quantity": "Quantity must be greater than zero" }
}
```

`fieldErrors` appears only for validation failures. Common codes: `VALIDATION_FAILED` (400),
`UNAUTHORIZED` (401), `FORBIDDEN` (403), `TRADING_BLOCKED` (403), `RATE_LIMITED` (429),
`NOT_FOUND` (404), `CONFLICT`/`ORDER_NOT_CANCELLABLE`/`CONCURRENT_MODIFICATION`/`DATA_INTEGRITY`
(409), `INSUFFICIENT_FUNDS`/`INSUFFICIENT_HOLDINGS` (422),
`MARKET_DATA_UNAVAILABLE` (503), `INTERNAL_ERROR` (500).

**Money.** All amounts are decimal strings/numbers with 4-decimal money precision. The client never
sends a financial value that feeds a calculation — price, charges, balance and P&L are always
derived server-side.

**Currencies.** New accounts use USD by default. Cash, order values, charges, P&L and risk are
stored in the account's currency. Instruments are tradeable only when their quote and settlement
currency match the account currency. TradeX has no live FX source, so orders requiring conversion
are rejected rather than using a fabricated rate. Existing INR accounts remain INR until an
explicit confirmed reset starts a new account session in the configured default currency. The
legacy operator-set `TRADEX_FX_USD_INR` rate may be used to value existing INR positions; it is a
simulation parameter, not a live FX feed.

**Paging.** List endpoints return:

```json
{ "content": [], "page": 0, "size": 25, "totalElements": 0, "totalPages": 0, "first": true, "last": true }
```

**Idempotency.** `POST /api/orders` accepts a client-generated key either as the `idempotencyKey`
body field or the `Idempotency-Key` header (the body wins). Replaying a key returns the original
order without executing again.

---

## Auth

### `POST /api/auth/register` → `201`

```json
{
  "email": "asha@example.com",
  "username": "asha",
  "fullName": "Asha Rao",          // optional, ≤120 chars
  "password": "minimum-8-chars",
  "initialCapital": 25000           // optional USD, $100–$1,000,000; default $10,000
}
```

Opens the user's virtual account and credits exactly one `INITIAL_GRANT` ledger entry for the
selected capital, or the configured default if `initialCapital` is omitted. Values outside the
configured range or with more than two decimal places are rejected. Configure the default and
bounds with `TRADEX_INITIAL_BALANCE`, `TRADEX_MINIMUM_INITIAL_BALANCE` and
`TRADEX_MAXIMUM_INITIAL_BALANCE`. Response:

```json
{
  "accessToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresInSeconds": 43200,
  "issuedAt": "...",
  "expiresAt": "...",
  "user": { "id": 7, "email": "...", "username": "asha", "fullName": "Asha Rao",
            "role": "USER", "createdAt": "..." }
}
```

`409` if the email or username is taken. A new account's `currency` is `USD` by default; its
initial grant and available balance are persisted independently for that user.

### `POST /api/auth/login` → `200`

```json
{ "identifier": "asha", "password": "minimum-8-chars" }
```

`identifier` accepts either the username or the email. `401` on bad credentials.

### `GET /api/auth/me` → `200`

Returns the `UserProfileDto` above.

### `POST /api/auth/logout` → `200`

Stateless: the token is not denylisted, it simply expires. The client discards it.

---

## Account

### `GET /api/account` → `200`

Full snapshot: `id`, `accountNumber`, `initialBalance`, `availableCash`, `reservedCash`,
`totalCash`, `holdingsValue`, `portfolioValue`, `investedCapital`, `unrealizedPnl`,
`unrealizedPnlPercent`, `realizedPnl`, `totalCharges`, `totalReturn`, `totalReturnPercent`,
`todaysPnl`, `openPositions`, `openOrders`, `dailyLossLimitEnabled`, `dailyLossLimit`,
`tradingBlocked`, `tradingBlockedReason`, `resetCount`, `lastResetAt`, `createdAt`.

### `GET /api/account/balance` → `200`

`accountNumber`, `currency`, `availableCash`, `reservedCash`, `totalCash`, `initialBalance`.

### `GET /api/account/summary` → `200`

The dashboard card payload includes `initialCapital`, `availableCash`, `reservedCash`,
`portfolioValue` (equity), invested cost basis, unrealised/realised P&L, total return, position
count, today's P&L and charges. Equity remains `available cash + reserved cash + marked holdings`;
invested cost basis is informational and must not be added again.

### `POST /api/account/reset` → `200`

```json
{ "confirm": true, "initialCapital": 50000, "dailyLossLimit": 5000, "dailyLossLimitEnabled": true }
```

`confirm` must be `true` (otherwise `400 RESET_NOT_CONFIRMED`). Cancels working orders, deletes
holdings/orders/trades, restores the balance to the supplied `initialCapital` (or the configured
default if omitted) **via an appended `ACCOUNT_RESET` ledger row** (the audit trail survives),
clears realised P&L, charges and any loss-limit breach, and increments `resetCount`. Reset also
sets the account currency to the configured default currency (USD by default), so resetting a
legacy INR account intentionally discards the old book instead of converting its balance.
Capital is range-validated on the server. The optional risk fields update the risk settings in the
same transaction.

---

## Market data (public)

No authentication required; these endpoints carry no user data.

| Endpoint | Notes |
| --- | --- |
| `GET /api/market/instruments?assetClass=EQUITY&tradableOnly=true&search=rel` | catalogue with tradeability flags |
| `GET /api/market/instruments/{symbol}` | one instrument |
| `GET /api/market/quote/{symbol}` | last/previous close, change, day range, volume, `currency`, `dataSource`, `dataSourceLabel`; instruments a live provider cannot serve carry `unavailable` + `unavailableReason` instead of numbers |
| `GET /api/market/quotes?symbols=RELIANCE,TCS` | batch quotes |
| `GET /api/market/candles/{symbol}?timeframe=15m&limit=200` | OHLCV in the instrument's `currency`; timeframes: `1m,5m,15m,30m,1h,4h,1d,1w`; `note` explains the data source when a live provider is active |
| `GET /api/market/indicators/{symbol}?timeframe=15m&limit=200&indicators=sma:20,ema:20,rsi:14` | all indicators when the filter is omitted; multi-argument specs use hyphens (`macd:12-26-9`, `bb:20-2`) |

Every series in the response is keyed by the **exact specification string you sent**
(`macd:12-26-9` in → `macd:12-26-9` back), and every array is index-aligned with the shared
`times` axis.
| `GET /api/market/timeframes` | supported timeframe codes |
| `GET /api/market/status` | market session state (`SIMULATED_ALWAYS_OPEN` in simulated mode) |
| `GET /api/market/providers` | the configured provider (`twelvedata` / `delta`) and whether it is ready |

In `live` mode, a provider's own forming candle is served as the last bar; the fallback path
replays only previously stored `LIVE` bars and never synthesises history.

Indicators are computed from the **same candle series** the chart endpoint returns; every indicator
value is `null` until enough history exists (never forward-filled).

Indices (`NIFTY50`, `SENSEX`, `BANKNIFTY`) are marked `tradable: false` — chart and analyse yes,
order placement no (`400 INSTRUMENT_NOT_TRADABLE`).

---

## Orders

### `POST /api/orders` → `201`

```json
{
  "symbol": "RELIANCE",
  "side": "BUY",                 // BUY | SELL
  "orderType": "LIMIT",          // MARKET | LIMIT | STOP_LOSS
  "quantity": 10,
  "limitPrice": 1250.00,         // required for LIMIT
  "stopPrice": null,             // required for STOP_LOSS, must sit on the correct side of the market
  "positionStopLoss": 1200.00,   // optional protective levels recorded on the position
  "positionTarget": 1400.00,
  "idempotencyKey": "order-4f2c..."   // optional; header Idempotency-Key also accepted
}
```

Response `OrderActionResponse`:

```json
{
  "order": { "id": 42, "orderRef": "ORD-...", "status": "PENDING", "reservedCash": 12542.13, ... },
  "message": "Limit order accepted and resting at 1250. It will execute when RELIANCE trades through your limit.",
  "availableCash": 87457.87,
  "reservedCash": 12542.13
}
```

Behaviour by type:

- **MARKET** executes immediately at the current quote (plus optional configured slippage).
- **LIMIT** executes immediately if marketable; otherwise it rests with `qty*limit + charges`
  reserved from spendable cash.
- **STOP_LOSS** rests until the market reaches the trigger; only then does a fill attempt happen.
  A buy stop must be above the market; a sell stop below it.

Validation is server-side and total: unknown symbols (`404`), non-tradable instruments (`400`),
zero/negative quantities (`400`), limit without price (`400`), stop on the wrong side (`400`),
insufficient cash for a reservation (`422 INSUFFICIENT_FUNDS`), selling more than the free
(unreserved) held quantity (`422 INSUFFICIENT_HOLDINGS`), breached daily-loss guard
(`403 TRADING_BLOCKED`).

### `POST /api/orders/estimate` → `200`

Same body minus the idempotency key. Returns the assumed price, order value, full charge breakdown,
total cost / net proceeds, whether funds and holdings suffice, and human-readable warnings. Places
nothing. For an instrument quoted outside the ledger currency, `assumedExecutionPrice` is in
`priceCurrency` and the response also carries `ledgerCurrency` and the `fxRate` used for the
converted amounts.

### `GET /api/orders` → `200`

Query: `tab=all|open|executed|cancelled|rejected|expired`, `symbol`, `side`, `from`, `to`
(ISO instants), `page`, `size` (≤200), `sort=createdAt|executedAt|status|quantity|avgFillPrice`
(`,asc` or `,desc`).

### `GET /api/orders/open` → `200`

Every working order (pending or partially filled), newest first.

### `GET /api/orders/{id}` → `200` · `404`

Only the owner can see an order; another user's id answers `404`, never `403`.

### `DELETE /api/orders/{id}` → `200`

Cancels a working order and releases its reservation. `409 ORDER_NOT_CANCELLABLE` if the order is
already terminal.

---

## Portfolio

### `GET /api/portfolio` → `200`

Cash split, invested/market/portfolio value, unrealised and realised P&L, total return, today's
P&L, `positionCount`, `openOrderCount`, the holdings array (quantity, average cost, current price,
market value, unrealised P&L, allocation %, attached stop/target, risk/reward) and the allocation
slices. Price fields carry the instrument `currency`; `fxNote` describes any currency conversion
applied to the ledger amounts (or is `null` when everything already is INR).

### `GET /api/portfolio/holdings` → `200`

Just the holdings array, marked at the latest quote.

### `GET /api/portfolio/performance?from=2026-09-15&to=2026-10-15` → `200`

Default window: the last 30 days, clamped to the account's first day. Returns `startingValue`,
`endingValue`, `netChange`/`netChangePercent`, realised/unrealised P&L, total charges, invested
capital, trade counts, win rate, average win/loss, profit factor, best/worst trade, max drawdown,
plus `equityCurve[]` and `dailyPnl[]` points.

---

## Trades

All trades are append-only; there is no endpoint that modifies or deletes one.

### `GET /api/trades` → `200`

Query: `symbol`, `side`, `from`, `to`, `result=all|profit|loss`, `page`, `size`. Returns a page of
`TradeDto`: refs, side, quantity, price, gross amount, charges, net amount, realised P&L, cost
basis, executed timestamp.

### `GET /api/trades/export` → `200 text/csv`

Same filters, no paging. RFC-4180 CSV (`Content-Disposition: attachment; filename="tradex-trades-....csv"`),
newest first — the exact file the Trades page downloads.

### `GET /api/trades/{id}` · `GET /api/trades/by-ref/{tradeRef}` → `200` · `404`

Single trade, owner-scoped.

---

## Risk

### `GET /api/risk/summary` → `200`

Daily P&L (realised + open), charges today, trade/order counts, exposure (`investedValue`,
`exposurePercent`, largest position), equity high-water mark and drawdown, the configured loss
limit and whether the guard is currently breached. `fxNote` describes any currency conversion used
by the open-position risk lines (or is `null`).

### `GET /api/risk/open-positions` → `200`

Per-position risk lines: quantity, average cost, market price, P&L, attached stop/target,
capital at risk and the risk/reward ratio.

### `POST /api/risk/position-size` → `200`

```json
{
  "symbol": "BTCUSD",           // optional; selects the instrument's price currency
  "entryPrice": 82000.00,
  "stopPrice": 80500.00,
  "targetPrice": 86000.00,      // optional
  "riskPercent": 1.5,           // % of portfolio value to risk
  "maxAllocationPercent": 25,   // optional cap
  "lotSize": 0.000001           // optional; decimals allowed (crypto lots are fractional)
}
```

Returns the suggested quantity (lot-normalised, fractional for crypto), capital at risk,
reward/risk ratio, allocation and any cap that bound the size. With a `symbol` whose quote currency
differs from the ledger, `riskPerUnit` and `breakEvenPrice` are in `priceCurrency` while the money
figures are in `ledgerCurrency` converted at `fxRate`.

### `GET /api/risk/journal?page=0&size=20` → `200`

### `POST /api/risk/journal` → `201`

```json
{ "title": "Breakout entry", "note": "...", "tag": "momentum",
  "symbol": "RELIANCE", "orderId": 42, "riskAmount": 1500, "rewardAmount": 3600 }
```

### `DELETE /api/risk/journal/{id}` → `204`

### `PUT /api/risk/settings` → `200`

```json
{
  "dailyLossLimit": 5000,
  "dailyLossLimitEnabled": true,
  "tradingBlocked": false,
  "clearBreach": true
}
```

All fields optional; omitted ones are untouched. Returns the updated account snapshot
(`tradingBlocked`, `dailyLossLimit`, `tradingBlockedReason`). A breached limit blocks new
risk-increasing orders with `403 TRADING_BLOCKED`; protective exits (attached stops/targets) keep
working. Clearing a breach does not raise the limit — the guard re-evaluates on the next order.

---

## Watchlist

| Endpoint | Behaviour |
| --- | --- |
| `GET /api/watchlist` | `{ items: [{ instrumentId, symbol, name, exchange, assetClass, sortOrder, quote }], dataSource, dataSourceLabel, asOf }` |
| `POST /api/watchlist` — `{ "symbol": "ITC" }` | `201` with the added item; `409` if already present |
| `DELETE /api/watchlist/{symbol}` | `204` |
| `GET /api/watchlist/catalogue` | every instrument, annotated with tradeability and provider support |

---

## Examples

```bash
BASE=http://localhost:8080

# Register and capture the token
TOKEN=$(curl -s -X POST $BASE/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"asha@example.com","username":"asha","password":"minimum-8-chars","fullName":"Asha Rao"}' \
  | jq -r .accessToken)

# Buy 5 RELIANCE at market, idempotently
curl -s -X POST $BASE/api/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: my-first-order' \
  -d '{"symbol":"RELIANCE","side":"BUY","orderType":"MARKET","quantity":5}' | jq

# Rest a limit buy below the market (cash becomes reserved, not spent)
curl -s -X POST $BASE/api/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"TCS","side":"BUY","orderType":"LIMIT","quantity":2,"limitPrice":1000}' | jq

# See it working, cancel it, watch the reservation come back
curl -s "$BASE/api/orders?tab=open" -H "Authorization: Bearer $TOKEN" | jq '.content[0].id'
curl -s -X DELETE $BASE/api/orders/<id> -H "Authorization: Bearer $TOKEN" | jq .status
curl -s $BASE/api/account/balance -H "Authorization: Bearer $TOKEN" | jq

# Export the trade blotter as CSV
curl -s "$BASE/api/trades/export" -H "Authorization: Bearer $TOKEN" -o trades.csv

# --- Live Delta Exchange mode (start the backend with
# --- TRADEX_MARKETDATA_PROVIDER=delta TRADEX_MARKETDATA_MODE=live) ---

# A real BTCUSD quote (USD) with the truthful source label
curl -s $BASE/api/market/quote/BTCUSD | jq '{lastPrice, currency, dataSource, dataSourceLabel}'

# A paper buy fills at the live price; ledger cash is converted at the nominal FX rate
curl -s -X POST $BASE/api/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"BTCUSD","side":"BUY","orderType":"MARKET","quantity":0.0005}' \
  | jq '{status: .order.status, avgFillPrice: .order.avgFillPrice, currency: .order.currency, availableCash}'

# NSE symbols have no Delta coverage: live mode says so instead of inventing a price
curl -s $BASE/api/market/quote/RELIANCE | jq '{available, unavailableReason}'
```

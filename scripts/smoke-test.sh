#!/usr/bin/env bash
# TradeX end-to-end smoke test against a running backend (default http://localhost:8080).
# Exercises: register -> summary -> quote -> buy -> holdings -> sell -> portfolio -> orders
# -> trades -> CSV -> risk -> watchlist -> daily-loss guard -> reset.
set -u

BASE="${BASE:-http://localhost:8080}"
USER="smoke_$(date +%s)"
PASS="TestPass123!"

j() { python3 -c "import sys,json;d=json.load(sys.stdin);print(eval('d'+sys.argv[1]))" "$1" 2>/dev/null; }
say() { printf '\n== %s ==\n' "$1"; }

say "register $USER"
REG=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"email\":\"$USER@example.com\",\"password\":\"$PASS\",\"fullName\":\"Smoke Test\"}")
echo "$REG" | head -c 400; echo
TOKEN=$(echo "$REG" | j "['accessToken']")
AUTH="Authorization: Bearer $TOKEN"

say "account summary (expect 100000 cash)"
curl -s "$BASE/api/account/summary" -H "$AUTH" | python3 -m json.tool | head -40

say "quote RELIANCE"
Q=$(curl -s "$BASE/api/market/quote/RELIANCE")
echo "$Q" | python3 -m json.tool | head -20
PRICE=$(echo "$Q" | j "['lastPrice']")
echo "price=$PRICE"

say "buy 10 RELIANCE MARKET"
BUY=$(curl -s -X POST "$BASE/api/orders" -H "$AUTH" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: smoke-buy-$USER" \
  -d '{"symbol":"RELIANCE","side":"BUY","orderType":"MARKET","quantity":10}')
echo "$BUY" | python3 -m json.tool | head -40

say "replay same idempotency key (must NOT place a second order)"
BUY2=$(curl -s -X POST "$BASE/api/orders" -H "$AUTH" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: smoke-buy-$USER" \
  -d '{"symbol":"RELIANCE","side":"BUY","orderType":"MARKET","quantity":10}')
echo "$BUY2" | j "['order']['orderRef']"

say "holdings"
curl -s "$BASE/api/portfolio/holdings" -H "$AUTH" | python3 -m json.tool | head -50

say "sell 4 RELIANCE MARKET"
SELL=$(curl -s -X POST "$BASE/api/orders" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"symbol":"RELIANCE","side":"SELL","orderType":"MARKET","quantity":4}')
echo "$SELL" | python3 -m json.tool | head -40

say "sell 9999 (must fail with insufficient holdings)"
curl -s -X POST "$BASE/api/orders" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"symbol":"INFY","side":"SELL","orderType":"MARKET","quantity":9999}' | python3 -m json.tool

say "buy beyond available cash (must fail)"
curl -s -X POST "$BASE/api/orders" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"symbol":"RELIANCE","side":"BUY","orderType":"MARKET","quantity":999999}' | python3 -m json.tool

say "account summary after trades"
curl -s "$BASE/api/account/summary" -H "$AUTH" | python3 -m json.tool | head -40

say "limit order far below market (stays pending)"
curl -s -X POST "$BASE/api/orders" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"symbol":"TCS","side":"BUY","orderType":"LIMIT","quantity":1,"limitPrice":1.00}' | python3 -m json.tool | head -25

say "open orders"
OPEN=$(curl -s "$BASE/api/orders/open" -H "$AUTH")
echo "$OPEN" | python3 -m json.tool | head -40
OID=$(echo "$OPEN" | j "[0]['id']")
CBAL_BEFORE=$(curl -s "$BASE/api/account/balance" -H "$AUTH" | j "['reservedCash']")
echo "reserved before cancel: $CBAL_BEFORE"

say "cancel the pending order (reservation released)"
curl -s -X DELETE "$BASE/api/orders/$OID" -H "$AUTH" | python3 -m json.tool | head -20
CBAL_AFTER=$(curl -s "$BASE/api/account/balance" -H "$AUTH" | j "['reservedCash']")
echo "reserved after cancel: $CBAL_AFTER"

say "portfolio value"
curl -s "$BASE/api/portfolio" -H "$AUTH" | python3 -m json.tool | head -60

say "performance (last 30 days)"
curl -s "$BASE/api/portfolio/performance" -H "$AUTH" | python3 -m json.tool | head -30

say "trades list"
curl -s "$BASE/api/trades?size=5" -H "$AUTH" | python3 -m json.tool | head -50

say "trades CSV export"
curl -s "$BASE/api/trades/export" -H "$AUTH" | head -5

say "risk summary"
curl -s "$BASE/api/risk/summary" -H "$AUTH" | python3 -m json.tool | head -60

say "position size calculator"
curl -s -X POST "$BASE/api/risk/position-size" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"entryPrice":100,"stopPrice":95,"targetPrice":115,"riskPercent":1,"maxAllocationPercent":25,"lotSize":1}' \
  | python3 -m json.tool | head -30

say "journal add + list"
curl -s -X POST "$BASE/api/risk/journal" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"title":"Smoke idea","note":"Bought the dip","tag":"swing","symbol":"RELIANCE","riskAmount":500,"rewardAmount":1500}' \
  | python3 -m json.tool | head -20
curl -s "$BASE/api/risk/journal" -H "$AUTH" | python3 -m json.tool | head -20

say "watchlist"
curl -s "$BASE/api/watchlist" -H "$AUTH" | python3 -m json.tool | head -40

say "candles 15m x 5"
curl -s "$BASE/api/market/candles/RELIANCE?timeframe=15m&limit=5" -H "$AUTH" | python3 -m json.tool | head -60

say "indicators"
curl -s "$BASE/api/market/indicators/RELIANCE?timeframe=15m&limit=200" -H "$AUTH" | python3 -c "import sys,json;d=json.load(sys.stdin);print({k:(len(v) if isinstance(v,list) else v) for k,v in d.items()})" 2>/dev/null

say "auth isolation: unauthenticated must 401, other user's order must 404"
curl -s -o /dev/null -w 'no-token -> %{http_code}\n' "$BASE/api/account/summary"
curl -s -o /dev/null -w 'bad-token -> %{http_code}\n' "$BASE/api/account/summary" -H 'Authorization: Bearer garbage'
USER2="smoke2_$(date +%s)"
REG2=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER2\",\"email\":\"$USER2@example.com\",\"password\":\"$PASS\",\"fullName\":\"Smoke Two\"}")
TOKEN2=$(echo "$REG2" | j "['accessToken']")
curl -s -o /dev/null -w "other user reading my order -> %{http_code}\n" "$BASE/api/orders/$OID" -H "Authorization: Bearer $TOKEN2"

say "reset account (confirm=true)"
curl -s -X POST "$BASE/api/account/reset" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"confirm":true}' | python3 -m json.tool | head -30
curl -s "$BASE/api/account/balance" -H "$AUTH" | python3 -m json.tool

say "SMOKE TEST COMPLETE"

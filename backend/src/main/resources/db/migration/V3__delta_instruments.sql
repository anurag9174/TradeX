-- =====================================================================================
-- TradeX :: V3 - Delta Exchange instruments (real market data)
--
-- BTCUSD and ETHUSD are Delta Exchange perpetual futures. Delta quotes crypto derivatives
-- in USD (no INR pairs, no spot), so `currency` is USD: the instrument's PRICE is shown
-- in dollars exactly as the exchange prints it. The V4 migration adds USD settlement and
-- contract quantities; orders require the account to share the quote/settlement currency.
-- Legacy account valuations may use the configured nominal FX rate, but it is not a live
-- conversion source. When the Delta provider is selected
-- (tradex.marketdata.provider=delta) these prices are real market data; in simulated mode
-- the seed price below boots the price model and the chart is labelled simulated.
-- =====================================================================================

INSERT INTO instruments
    (symbol, name, exchange, asset_class, currency, data_provider, provider_symbol, provider_supported,
     tick_size, lot_size, tradable, active, sort_order, seed_price, volatility, sector, created_at, updated_at)
VALUES
    ('BTCUSD', 'Bitcoin / US Dollar Perpetual',   'DELTA', 'CRYPTO', 'USD', 'SIMULATED', 'DELTA:BTCUSD', TRUE, 0.50000000, 0.00000100, TRUE, TRUE, 300, 82000.0000, 0.040000, 'Crypto', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ETHUSD', 'Ethereum / US Dollar Perpetual',  'DELTA', 'CRYPTO', 'USD', 'SIMULATED', 'DELTA:ETHUSD', TRUE, 0.05000000, 0.00010000, TRUE, TRUE, 310,  3250.0000, 0.045000, 'Crypto', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- New accounts get the USD perpetuals appended to the default watchlist.
INSERT INTO watchlist_template (symbol, sort_order) VALUES
    ('BTCUSD', 90),
    ('ETHUSD', 100);

-- =====================================================================================
-- TradeX :: V2 - development instrument universe
--
-- Symbols are the *canonical* platform identifiers. `provider_symbol` holds a
-- provider-agnostic  VENUE:TICKER  reference which each market-data adapter translates into
-- its own request format (see com.tradex.marketdata.*).
--
-- `seed_price` is only ever used to bootstrap the SIMULATED price process. When a real
-- provider is configured the seed is ignored and provider_symbol is used instead. Prices
-- are NOT real market prices and are labelled as simulated everywhere in the UI.
-- =====================================================================================

INSERT INTO instruments
    (symbol, name, exchange, asset_class, currency, data_provider, provider_symbol, provider_supported,
     tick_size, lot_size, tradable, active, sort_order, seed_price, volatility, sector, created_at, updated_at)
VALUES
    ('NIFTY50',    'NIFTY 50',                    'NSE', 'INDEX',  'INR', 'SIMULATED', 'NSE:NIFTY50',    TRUE, 0.05000000, 1.00000000, FALSE, TRUE,  10,   24850.0000, 0.009000, 'Index',      CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('SENSEX',     'BSE SENSEX',                  'BSE', 'INDEX',  'INR', 'SIMULATED', 'BSE:SENSEX',     TRUE, 0.05000000, 1.00000000, FALSE, TRUE,  20,   81250.0000, 0.009500, 'Index',      CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('BANKNIFTY',  'NIFTY BANK',                  'NSE', 'INDEX',  'INR', 'SIMULATED', 'NSE:BANKNIFTY',  TRUE, 0.05000000, 1.00000000, FALSE, TRUE,  30,   53200.0000, 0.011000, 'Index',      CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RELIANCE',   'Reliance Industries Ltd',     'NSE', 'EQUITY', 'INR', 'SIMULATED', 'NSE:RELIANCE',   TRUE, 0.05000000, 1.00000000, TRUE,  TRUE, 100,    1290.0000, 0.015000, 'Energy',     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('TCS',        'Tata Consultancy Services Ltd','NSE','EQUITY', 'INR', 'SIMULATED', 'NSE:TCS',        TRUE, 0.05000000, 1.00000000, TRUE,  TRUE, 110,    4120.0000, 0.014000, 'IT',         CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('INFY',       'Infosys Ltd',                 'NSE', 'EQUITY', 'INR', 'SIMULATED', 'NSE:INFY',       TRUE, 0.05000000, 1.00000000, TRUE,  TRUE, 120,    1875.0000, 0.016000, 'IT',         CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('HDFCBANK',   'HDFC Bank Ltd',               'NSE', 'EQUITY', 'INR', 'SIMULATED', 'NSE:HDFCBANK',   TRUE, 0.05000000, 1.00000000, TRUE,  TRUE, 130,    1690.0000, 0.013000, 'Banking',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ICICIBANK',  'ICICI Bank Ltd',              'NSE', 'EQUITY', 'INR', 'SIMULATED', 'NSE:ICICIBANK',  TRUE, 0.05000000, 1.00000000, TRUE,  TRUE, 140,    1245.0000, 0.014000, 'Banking',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('SBIN',       'State Bank of India',         'NSE', 'EQUITY', 'INR', 'SIMULATED', 'NSE:SBIN',       TRUE, 0.05000000, 1.00000000, TRUE,  TRUE, 150,     815.0000, 0.017000, 'Banking',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('TATAMOTORS', 'Tata Motors Ltd',             'NSE', 'EQUITY', 'INR', 'SIMULATED', 'NSE:TATAMOTORS', TRUE, 0.05000000, 1.00000000, TRUE,  TRUE, 160,     960.0000, 0.022000, 'Auto',       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('WIPRO',      'Wipro Ltd',                   'NSE', 'EQUITY', 'INR', 'SIMULATED', 'NSE:WIPRO',      TRUE, 0.05000000, 1.00000000, TRUE,  TRUE, 170,     540.0000, 0.015000, 'IT',         CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ITC',        'ITC Ltd',                     'NSE', 'EQUITY', 'INR', 'SIMULATED', 'NSE:ITC',        TRUE, 0.05000000, 1.00000000, TRUE,  TRUE, 180,     468.0000, 0.011000, 'FMCG',       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('BHARTIARTL', 'Bharti Airtel Ltd',           'NSE', 'EQUITY', 'INR', 'SIMULATED', 'NSE:BHARTIARTL', TRUE, 0.05000000, 1.00000000, TRUE,  TRUE, 190,    1580.0000, 0.013000, 'Telecom',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('BTCINR',     'Bitcoin / Indian Rupee',      'CRYPTO','CRYPTO','INR', 'SIMULATED', 'CRYPTO:BTC/INR', TRUE, 1.00000000, 0.00000100, TRUE,  TRUE, 200, 5850000.0000, 0.045000, 'Crypto',     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ETHINR',     'Ethereum / Indian Rupee',     'CRYPTO','CRYPTO','INR', 'SIMULATED', 'CRYPTO:ETH/INR', TRUE, 0.50000000, 0.00000100, TRUE,  TRUE, 210,  210000.0000, 0.050000, 'Crypto',     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
;

-- -------------------------------------------------------------------------------------
-- Default watchlist template. Rows are copied per account at registration time so that
-- each user owns an independent, editable watchlist.
-- -------------------------------------------------------------------------------------
CREATE TABLE watchlist_template (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    symbol        VARCHAR(40) NOT NULL,
    sort_order    INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_watchlist_template_symbol UNIQUE (symbol)
);

INSERT INTO watchlist_template (symbol, sort_order) VALUES
    ('NIFTY50', 10),
    ('SENSEX', 20),
    ('RELIANCE', 30),
    ('TCS', 40),
    ('INFY', 50),
    ('HDFCBANK', 60),
    ('BTCINR', 70),
    ('ETHINR', 80);

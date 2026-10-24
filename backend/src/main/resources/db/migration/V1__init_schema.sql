-- =====================================================================================
-- TradeX :: V1 - initial schema
--
-- Written with portable DDL so that it executes unchanged on MySQL 8.x (production /
-- development) and on H2 running in MODE=MySQL (automated tests).
--
-- Money  : DECIMAL(19,4)  - four decimal places is more than enough for INR paise.
-- Prices : DECIMAL(19,6)  - six decimal places so crypto quantities stay lossless.
-- Time   : DATETIME(6), always stored in UTC.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- users
-- -------------------------------------------------------------------------------------
CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(190) NOT NULL,
    username      VARCHAR(60)  NOT NULL,
    full_name     VARCHAR(120),
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_username UNIQUE (username)
);

-- -------------------------------------------------------------------------------------
-- trading_accounts - exactly one per user
-- -------------------------------------------------------------------------------------
CREATE TABLE trading_accounts (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    user_id                 BIGINT        NOT NULL,
    account_number          VARCHAR(40)   NOT NULL,
    display_name            VARCHAR(80),
    initial_balance         DECIMAL(19,4) NOT NULL,
    cash_balance            DECIMAL(19,4) NOT NULL,
    reserved_cash           DECIMAL(19,4) NOT NULL,
    realized_pnl            DECIMAL(19,4) NOT NULL,
    total_charges           DECIMAL(19,4) NOT NULL,
    daily_loss_limit        DECIMAL(19,4) NOT NULL DEFAULT 0,
    daily_loss_limit_enabled BOOLEAN      NOT NULL DEFAULT FALSE,
    loss_limit_breach_date  DATE,
    trading_blocked         BOOLEAN       NOT NULL DEFAULT FALSE,
    reset_count             INT           NOT NULL DEFAULT 0,
    last_reset_at           DATETIME(6),
    created_at              DATETIME(6)   NOT NULL,
    updated_at              DATETIME(6)   NOT NULL,
    version                 BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_trading_accounts_user UNIQUE (user_id),
    CONSTRAINT uk_trading_accounts_number UNIQUE (account_number),
    CONSTRAINT fk_trading_accounts_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- -------------------------------------------------------------------------------------
-- instruments
-- -------------------------------------------------------------------------------------
CREATE TABLE instruments (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    symbol             VARCHAR(40)   NOT NULL,
    name               VARCHAR(120)  NOT NULL,
    exchange           VARCHAR(40)   NOT NULL,
    asset_class        VARCHAR(20)   NOT NULL,
    currency           VARCHAR(8)    NOT NULL DEFAULT 'INR',
    data_provider      VARCHAR(40)   NOT NULL DEFAULT 'SIMULATED',
    provider_symbol    VARCHAR(80),
    provider_supported BOOLEAN       NOT NULL DEFAULT TRUE,
    tick_size          DECIMAL(19,8) NOT NULL,
    lot_size           DECIMAL(19,8) NOT NULL,
    tradable           BOOLEAN       NOT NULL DEFAULT TRUE,
    active             BOOLEAN       NOT NULL DEFAULT TRUE,
    sort_order         INT           NOT NULL DEFAULT 0,
    seed_price         DECIMAL(19,4) NOT NULL,
    volatility         DECIMAL(10,6) NOT NULL DEFAULT 0.02,
    sector             VARCHAR(60),
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_instruments_symbol UNIQUE (symbol)
);

-- -------------------------------------------------------------------------------------
-- market_candles
-- -------------------------------------------------------------------------------------
CREATE TABLE market_candles (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    instrument_id BIGINT        NOT NULL,
    timeframe     VARCHAR(8)    NOT NULL,
    open_time     DATETIME(6)   NOT NULL,
    open_price    DECIMAL(19,6) NOT NULL,
    high_price    DECIMAL(19,6) NOT NULL,
    low_price     DECIMAL(19,6) NOT NULL,
    close_price   DECIMAL(19,6) NOT NULL,
    volume        DECIMAL(19,4) NOT NULL DEFAULT 0,
    source        VARCHAR(20)   NOT NULL DEFAULT 'SIMULATED',
    created_at    DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_market_candles_instrument_interval_time UNIQUE (instrument_id, timeframe, open_time),
    CONSTRAINT fk_market_candles_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id) ON DELETE CASCADE
);

-- -------------------------------------------------------------------------------------
-- holdings
-- -------------------------------------------------------------------------------------
CREATE TABLE holdings (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    account_id              BIGINT        NOT NULL,
    instrument_id           BIGINT        NOT NULL,
    quantity                DECIMAL(19,6) NOT NULL,
    avg_cost                DECIMAL(19,6) NOT NULL,
    accumulated_buy_charges DECIMAL(19,4) NOT NULL DEFAULT 0,
    realized_pnl            DECIMAL(19,4) NOT NULL DEFAULT 0,
    stop_loss_price         DECIMAL(19,4),
    target_price            DECIMAL(19,4),
    opened_at               DATETIME(6),
    last_traded_at          DATETIME(6),
    created_at              DATETIME(6)   NOT NULL,
    updated_at              DATETIME(6)   NOT NULL,
    version                 BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_holdings_account_instrument UNIQUE (account_id, instrument_id),
    CONSTRAINT fk_holdings_account FOREIGN KEY (account_id) REFERENCES trading_accounts (id) ON DELETE CASCADE,
    CONSTRAINT fk_holdings_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id)
);

-- -------------------------------------------------------------------------------------
-- orders
-- -------------------------------------------------------------------------------------
CREATE TABLE orders (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    order_ref         VARCHAR(40)   NOT NULL,
    account_id        BIGINT        NOT NULL,
    instrument_id     BIGINT        NOT NULL,
    side              VARCHAR(8)    NOT NULL,
    order_type        VARCHAR(16)   NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    quantity          DECIMAL(19,6) NOT NULL,
    limit_price       DECIMAL(19,4),
    stop_price        DECIMAL(19,4),
    position_stop_loss DECIMAL(19,4),
    position_target   DECIMAL(19,4),
    filled_quantity   DECIMAL(19,6) NOT NULL DEFAULT 0,
    avg_fill_price    DECIMAL(19,4),
    reserved_cash     DECIMAL(19,4) NOT NULL DEFAULT 0,
    estimated_charges DECIMAL(19,4) NOT NULL DEFAULT 0,
    charges           DECIMAL(19,4) NOT NULL DEFAULT 0,
    realized_pnl      DECIMAL(19,4) NOT NULL DEFAULT 0,
    net_cash_impact   DECIMAL(19,4) NOT NULL DEFAULT 0,
    reject_reason     VARCHAR(255),
    idempotency_key   VARCHAR(80),
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    executed_at       DATETIME(6),
    cancelled_at      DATETIME(6),
    triggered_at      DATETIME(6),
    version           BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_orders_ref UNIQUE (order_ref),
    CONSTRAINT uk_orders_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_orders_account FOREIGN KEY (account_id) REFERENCES trading_accounts (id) ON DELETE CASCADE,
    CONSTRAINT fk_orders_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id)
);

-- -------------------------------------------------------------------------------------
-- trades - append only
-- -------------------------------------------------------------------------------------
CREATE TABLE trades (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    trade_ref     VARCHAR(40)   NOT NULL,
    execution_key VARCHAR(80)   NOT NULL,
    order_id      BIGINT        NOT NULL,
    account_id    BIGINT        NOT NULL,
    instrument_id BIGINT        NOT NULL,
    side          VARCHAR(8)    NOT NULL,
    quantity      DECIMAL(19,6) NOT NULL,
    price         DECIMAL(19,4) NOT NULL,
    gross_amount  DECIMAL(19,4) NOT NULL,
    charges       DECIMAL(19,4) NOT NULL DEFAULT 0,
    net_amount    DECIMAL(19,4) NOT NULL,
    realized_pnl  DECIMAL(19,4) NOT NULL DEFAULT 0,
    cost_basis    DECIMAL(19,6),
    executed_at   DATETIME(6)   NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_trades_ref UNIQUE (trade_ref),
    CONSTRAINT uk_trades_execution_key UNIQUE (execution_key),
    CONSTRAINT fk_trades_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_trades_account FOREIGN KEY (account_id) REFERENCES trading_accounts (id) ON DELETE CASCADE,
    CONSTRAINT fk_trades_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id)
);

-- -------------------------------------------------------------------------------------
-- transaction_ledger - append only, never updated
-- -------------------------------------------------------------------------------------
CREATE TABLE transaction_ledger (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    account_id     BIGINT        NOT NULL,
    entry_type     VARCHAR(30)   NOT NULL,
    amount         DECIMAL(19,4) NOT NULL,
    reserved_delta DECIMAL(19,4) NOT NULL DEFAULT 0,
    balance_after  DECIMAL(19,4) NOT NULL,
    reserved_after DECIMAL(19,4) NOT NULL,
    order_id       BIGINT,
    trade_id       BIGINT,
    description    VARCHAR(255),
    created_at     DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ledger_account FOREIGN KEY (account_id) REFERENCES trading_accounts (id) ON DELETE CASCADE
);

-- -------------------------------------------------------------------------------------
-- watchlist_items
-- -------------------------------------------------------------------------------------
CREATE TABLE watchlist_items (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    account_id    BIGINT      NOT NULL,
    instrument_id BIGINT      NOT NULL,
    sort_order    INT         NOT NULL DEFAULT 0,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_watchlist_account_instrument UNIQUE (account_id, instrument_id),
    CONSTRAINT fk_watchlist_account FOREIGN KEY (account_id) REFERENCES trading_accounts (id) ON DELETE CASCADE,
    CONSTRAINT fk_watchlist_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id)
);

-- -------------------------------------------------------------------------------------
-- journal_entries
-- -------------------------------------------------------------------------------------
CREATE TABLE journal_entries (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    account_id    BIGINT        NOT NULL,
    instrument_id BIGINT,
    order_id      BIGINT,
    title         VARCHAR(140)  NOT NULL,
    note          VARCHAR(2000),
    tag           VARCHAR(40),
    risk_amount   DECIMAL(19,4),
    reward_amount DECIMAL(19,4),
    created_at    DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_journal_account FOREIGN KEY (account_id) REFERENCES trading_accounts (id) ON DELETE CASCADE
);

-- =====================================================================================
-- Indexes
-- =====================================================================================
CREATE INDEX idx_trading_accounts_user ON trading_accounts (user_id);

CREATE INDEX idx_instruments_asset_class ON instruments (asset_class);
CREATE INDEX idx_instruments_active ON instruments (active);

CREATE INDEX idx_market_candles_lookup ON market_candles (instrument_id, timeframe, open_time);
CREATE INDEX idx_market_candles_source ON market_candles (source);

CREATE INDEX idx_holdings_account ON holdings (account_id);

CREATE INDEX idx_orders_account_status ON orders (account_id, status);
CREATE INDEX idx_orders_account_created ON orders (account_id, created_at);
CREATE INDEX idx_orders_working ON orders (status, order_type);
CREATE INDEX idx_orders_instrument ON orders (instrument_id);

CREATE INDEX idx_trades_account_time ON trades (account_id, executed_at);
CREATE INDEX idx_trades_account_instrument ON trades (account_id, instrument_id);
CREATE INDEX idx_trades_order ON trades (order_id);

CREATE INDEX idx_ledger_account_time ON transaction_ledger (account_id, created_at);
CREATE INDEX idx_ledger_account_type ON transaction_ledger (account_id, entry_type);
CREATE INDEX idx_ledger_order ON transaction_ledger (order_id);
CREATE INDEX idx_ledger_trade ON transaction_ledger (trade_id);

CREATE INDEX idx_watchlist_account ON watchlist_items (account_id);

CREATE INDEX idx_journal_account_time ON journal_entries (account_id, created_at);

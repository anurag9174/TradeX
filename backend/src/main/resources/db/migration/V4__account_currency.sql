ALTER TABLE trading_accounts
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'INR';

ALTER TABLE transaction_ledger
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'INR';

ALTER TABLE instruments
    ADD COLUMN settlement_currency VARCHAR(8) NOT NULL DEFAULT 'INR';

UPDATE instruments SET settlement_currency = currency;

-- Delta's linear USD perpetuals express order quantity in the underlying asset:
-- each BTCUSD lot is 0.001 BTC and each ETHUSD lot is 0.01 ETH; both settle in USD.
UPDATE instruments SET lot_size = 0.001, settlement_currency = 'USD' WHERE symbol = 'BTCUSD';
UPDATE instruments SET lot_size = 0.01, settlement_currency = 'USD' WHERE symbol = 'ETHUSD';

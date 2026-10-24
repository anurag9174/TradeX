package com.tradex.enums;

/** Kind of movement recorded in the immutable transaction ledger. */
public enum LedgerEntryType {
    /** Opening grant of virtual cash (exactly once per account). */
    INITIAL_GRANT,
    /** Explicit user-triggered reset back to the starting balance. */
    ACCOUNT_RESET,
    /** Cash paid out to acquire an asset. */
    BUY_SETTLEMENT,
    /** Cash received from disposing of an asset. */
    SELL_SETTLEMENT,
    /** Settlement charges (brokerage, taxes, duties). */
    CHARGES,
    /** Cash reserved while a buy limit/stop order rests on the book. */
    CASH_RESERVED,
    /** Reservation released when an order is cancelled, rejected or expired. */
    CASH_RELEASED,
    /** Administrative correction. */
    ADJUSTMENT
}

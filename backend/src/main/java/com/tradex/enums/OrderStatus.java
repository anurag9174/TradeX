package com.tradex.enums;

/**
 * Lifecycle of an order.
 *
 * <p>{@link #PENDING} and {@link #PARTIALLY_FILLED} are "open" states: they can still be
 * cancelled and may still fill. Every other state is terminal.</p>
 */
public enum OrderStatus {
    /** Accepted by the engine, resting on the book (limit / stop orders). */
    PENDING,
    /** Partially executed. Kept for forward compatibility; the v1 engine fills in one shot. */
    PARTIALLY_FILLED,
    /** Fully executed. */
    EXECUTED,
    /** Cancelled by the user before execution. */
    CANCELLED,
    /** Rejected by validation (funds, holdings, bad request, daily loss limit, ...). */
    REJECTED,
    /** Expired without executing (e.g. end-of-day cleanup). */
    EXPIRED;

    public boolean isOpen() {
        return this == PENDING || this == PARTIALLY_FILLED;
    }

    public boolean isTerminal() {
        return !isOpen();
    }
}

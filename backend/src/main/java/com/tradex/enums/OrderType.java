package com.tradex.enums;

/**
 * Supported order types.
 *
 * <ul>
 *   <li>{@link #MARKET} - fills immediately at the prevailing simulated price.</li>
 *   <li>{@link #LIMIT} - rests until the market price satisfies {@code limitPrice}.</li>
 *   <li>{@link #STOP_LOSS} - rests until the market price triggers {@code stopPrice}, then fills
 *       as a market order.</li>
 * </ul>
 */
public enum OrderType {
    MARKET,
    LIMIT,
    STOP_LOSS;

    /** Market orders execute on submission; every other type rests on the book. */
    public boolean isImmediate() {
        return this == MARKET;
    }
}

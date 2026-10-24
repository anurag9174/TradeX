package com.tradex.enums;

/** Broad market session state used by the UI header. */
public enum MarketStatus {
    OPEN,
    CLOSED,
    /** Simulated venue: always tradable because the tape never sleeps. */
    SIMULATED_ALWAYS_OPEN
}

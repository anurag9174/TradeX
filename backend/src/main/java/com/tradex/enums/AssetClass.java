package com.tradex.enums;

/** Asset classes understood by the platform. */
public enum AssetClass {
    /** Non-tradable cash index (e.g. NIFTY 50). Charts + analytics only. */
    INDEX,
    /** Cash equity. */
    EQUITY,
    /** Crypto / digital asset pair. */
    CRYPTO
}

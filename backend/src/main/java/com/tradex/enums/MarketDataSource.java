package com.tradex.enums;

/** Where the market data on screen actually came from. Surfaced in every quote response. */
public enum MarketDataSource {
    /** Deterministically generated candle/quote stream. NOT a real market price. */
    SIMULATED,
    /** Live data from the configured third-party provider. */
    LIVE
}

package com.tradex.exception;

import org.springframework.http.HttpStatus;

/** Raised when no price can be obtained for an instrument. Never silently replaced by a fake price. */
public class MarketDataUnavailableException extends ApiException {

    public MarketDataUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_DATA_UNAVAILABLE", message);
    }

    public static MarketDataUnavailableException unsupported(String symbol, String provider) {
        return new MarketDataUnavailableException(
                "Instrument " + symbol + " is not available from the configured market data provider '"
                        + provider + "'. Live price is unavailable; no simulated substitute was used.");
    }
}

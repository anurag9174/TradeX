package com.tradex.exception;

import org.springframework.http.HttpStatus;

/** Raised when the account's configured simulated daily-loss guard has been breached. */
public class TradingBlockedException extends ApiException {

    public TradingBlockedException(String message) {
        super(HttpStatus.FORBIDDEN, "TRADING_BLOCKED", message);
    }
}

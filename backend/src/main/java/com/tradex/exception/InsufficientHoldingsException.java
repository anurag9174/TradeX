package com.tradex.exception;

import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

/** Raised when a sell order exceeds the quantity actually held. */
public class InsufficientHoldingsException extends ApiException {

    public InsufficientHoldingsException(BigDecimal requested, BigDecimal held, String symbol) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_HOLDINGS",
                "Cannot sell " + requested.stripTrailingZeros().toPlainString() + " of " + symbol
                        + ": only " + held.stripTrailingZeros().toPlainString() + " is held");
    }
}

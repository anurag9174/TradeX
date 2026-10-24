package com.tradex.exception;

import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

/** Raised when a buy order (or a reservation) would overdraw the virtual account. */
public class InsufficientFundsException extends ApiException {

    public InsufficientFundsException(BigDecimal required, BigDecimal available) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                "Insufficient virtual balance: required " + required.toPlainString()
                        + " but only " + available.toPlainString() + " is available");
    }
}

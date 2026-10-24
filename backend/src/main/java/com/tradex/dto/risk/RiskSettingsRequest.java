package com.tradex.dto.risk;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

import java.math.BigDecimal;

/** Update the account's simulated risk guards. */
public record RiskSettingsRequest(
        @DecimalMin(value = "0.0") @Digits(integer = 15, fraction = 4) BigDecimal dailyLossLimit,
        Boolean dailyLossLimitEnabled,
        Boolean tradingBlocked,
        /** Sending {@code true} clears the breach flag so trading resumes without a full reset. */
        Boolean clearBreach) {

    public boolean hasDailyLossLimit() {
        return dailyLossLimit != null;
    }

    public boolean hasDailyLossLimitEnabled() {
        return dailyLossLimitEnabled != null;
    }

    public boolean hasTradingBlocked() {
        return tradingBlocked != null;
    }

    public boolean shouldClearBreach() {
        return Boolean.TRUE.equals(clearBreach);
    }
}

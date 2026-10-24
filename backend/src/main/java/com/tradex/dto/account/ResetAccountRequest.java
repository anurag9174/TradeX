package com.tradex.dto.account;

import java.math.BigDecimal;

public record ResetAccountRequest(
        /** Explicitly confirms destructive reset behavior. */
        Boolean confirm,
        /** Optional new daily loss limit applied after the reset. Null keeps the current value. */
        BigDecimal dailyLossLimit,
        Boolean dailyLossLimitEnabled,
        /** Optional opening capital for the new virtual-account session. */
        BigDecimal initialCapital) {

    public boolean isConfirmed() {
        return Boolean.TRUE.equals(confirm);
    }

    public boolean hasDailyLossLimit() {
        return dailyLossLimit != null;
    }

    public boolean hasDailyLossLimitEnabled() {
        return dailyLossLimitEnabled != null;
    }

    public boolean hasInitialCapital() {
        return initialCapital != null;
    }
}

package com.tradex.dto.account;

import java.math.BigDecimal;
import java.time.Instant;

public record ResetAccountResponse(
        String accountNumber,
        String currency,
        BigDecimal availableCash,
        String previousCurrency,
        BigDecimal previousCash,
        BigDecimal previousHoldingsValue,
        int positionsLiquidated,
        int tradesArchived,
        int ordersArchived,
        int resetCount,
        Instant resetAt,
        String message) {
}

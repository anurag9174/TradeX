package com.tradex.dto.account;

import java.math.BigDecimal;

/** Lightweight body for {@code GET /api/account/balance}. */
public record BalanceDto(
        String accountNumber,
        String currency,
        BigDecimal availableCash,
        BigDecimal reservedCash,
        BigDecimal totalCash,
        BigDecimal initialBalance) {
}

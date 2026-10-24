package com.tradex.dto.risk;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateJournalEntryRequest(
        @NotBlank(message = "Title is required")
        @Size(max = 140)
        String title,

        @Size(max = 2000)
        String note,

        @Size(max = 40)
        String tag,

        @Size(max = 40)
        String symbol,

        Long orderId,

        @DecimalMin(value = "0.0") @Digits(integer = 15, fraction = 4)
        BigDecimal riskAmount,

        @DecimalMin(value = "0.0") @Digits(integer = 15, fraction = 4)
        BigDecimal rewardAmount) {
}

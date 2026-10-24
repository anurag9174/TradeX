package com.tradex.dto.risk;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public record JournalEntryDto(
        Long id,
        Long instrumentId,
        String symbol,
        Long orderId,
        String title,
        String note,
        String tag,
        BigDecimal riskAmount,
        BigDecimal rewardAmount,
        BigDecimal riskRewardRatio,
        Instant createdAt) {
}

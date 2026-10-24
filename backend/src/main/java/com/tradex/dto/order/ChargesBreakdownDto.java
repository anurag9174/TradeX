package com.tradex.dto.order;

import java.math.BigDecimal;

/** Itemised simulated transaction charges, computed server-side only. */
public record ChargesBreakdownDto(
        BigDecimal brokerage,
        BigDecimal exchangeTransactionCharge,
        BigDecimal sebiTurnoverCharge,
        BigDecimal gst,
        BigDecimal stampDuty,
        BigDecimal dpCharges,
        BigDecimal total) {

    public static ChargesBreakdownDto zero() {
        return new ChargesBreakdownDto(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}

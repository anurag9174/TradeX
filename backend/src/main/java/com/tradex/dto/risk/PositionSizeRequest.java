package com.tradex.dto.risk;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Inputs for the position-size calculator. All percentages are whole numbers (2 == 2%). */
public record PositionSizeRequest(
        @NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal entryPrice,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal stopPrice,
        @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal targetPrice,
        /** Percentage of the portfolio the trader is willing to lose on this idea. */
        @NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 3, fraction = 4) BigDecimal riskPercent,
        /** Optional cap on notional exposure as a percentage of the portfolio. */
        @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 3, fraction = 4) BigDecimal maxAllocationPercent,
        /** Lot / step size the suggested quantity is floored to. Fractional for crypto venues. */
        @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 13, fraction = 6) BigDecimal lotSize,
        /**
         * Optional instrument symbol. When given, prices are interpreted in that instrument's quote
         * currency and money amounts are converted to the ledger currency at the nominal FX rate.
         */
        @Size(max = 40) String symbol) {
}

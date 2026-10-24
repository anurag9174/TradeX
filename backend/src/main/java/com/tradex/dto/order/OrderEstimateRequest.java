package com.tradex.dto.order;

import com.tradex.enums.OrderSide;
import com.tradex.enums.OrderType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Pre-trade preview so the order panel can show value, charges and affordability before submit. */
public record OrderEstimateRequest(
        @NotBlank @Size(max = 40) String symbol,
        @NotNull OrderSide side,
        @NotNull OrderType orderType,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 13, fraction = 6) BigDecimal quantity,
        @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal limitPrice,
        @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal stopPrice) {
}

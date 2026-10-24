package com.tradex.dto.order;

import com.tradex.enums.OrderSide;
import com.tradex.enums.OrderType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Order-entry payload.
 *
 * <p>Note what is <em>absent</em>: price, balance, charges and P&amp;L. The client never supplies
 * any value that feeds a financial calculation - it only names the instrument and the intent.</p>
 */
public record PlaceOrderRequest(
        @NotBlank(message = "Symbol is required")
        @Size(max = 40)
        String symbol,

        @NotNull(message = "Side is required (BUY or SELL)")
        OrderSide side,

        @NotNull(message = "Order type is required (MARKET, LIMIT or STOP_LOSS)")
        OrderType orderType,

        @NotNull(message = "Quantity is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "Quantity must be greater than zero")
        @Digits(integer = 13, fraction = 6, message = "Quantity supports at most 6 decimal places")
        BigDecimal quantity,

        /** Required for LIMIT orders. Ignored for market orders. */
        @DecimalMin(value = "0.0", inclusive = false, message = "Limit price must be greater than zero")
        @Digits(integer = 15, fraction = 4)
        BigDecimal limitPrice,

        /** Required for STOP_LOSS orders; must be on the correct side of the market. */
        @DecimalMin(value = "0.0", inclusive = false, message = "Stop price must be greater than zero")
        @Digits(integer = 15, fraction = 4)
        BigDecimal stopPrice,

        /** Optional protective stop recorded against the resulting position (risk helper). */
        @DecimalMin(value = "0.0", inclusive = false)
        @Digits(integer = 15, fraction = 4)
        BigDecimal positionStopLoss,

        /** Optional profit target recorded against the resulting position (risk helper). */
        @DecimalMin(value = "0.0", inclusive = false)
        @Digits(integer = 15, fraction = 4)
        BigDecimal positionTarget,

        /** Optional client-generated key; replays of the same key return the original order. */
        @Size(max = 80)
        String idempotencyKey) {
}

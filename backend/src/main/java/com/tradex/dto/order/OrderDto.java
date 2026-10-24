package com.tradex.dto.order;

import com.tradex.enums.OrderSide;
import com.tradex.enums.OrderStatus;
import com.tradex.enums.OrderType;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderDto(
        Long id,
        String orderRef,
        String symbol,
        String instrumentName,
        String exchange,
        /** Quote currency of the instrument ({@code INR}, {@code USD}); prices are printed in it. */
        String currency,
        /** Currency used by cash, charges, P&L and other account-level amounts. */
        String accountCurrency,

        OrderSide side,
        OrderType orderType,
        OrderStatus status,
        String statusLabel,

        BigDecimal quantity,
        BigDecimal limitPrice,
        BigDecimal stopPrice,
        BigDecimal filledQuantity,
        BigDecimal avgFillPrice,
        BigDecimal orderValue,
        BigDecimal executedValue,

        BigDecimal estimatedCharges,
        BigDecimal charges,
        ChargesBreakdownDto chargeBreakdown,

        BigDecimal reservedCash,
        BigDecimal realizedPnl,
        BigDecimal netCashImpact,

        BigDecimal positionStopLoss,
        BigDecimal positionTarget,

        String rejectReason,
        Instant createdAt,
        Instant updatedAt,
        Instant triggeredAt,
        Instant executedAt,
        Instant cancelledAt,

        /** True when the order can still be cancelled through DELETE /api/orders/{id}. */
        boolean cancellable) {
}

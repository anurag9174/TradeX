package com.tradex.dto.order;

import java.math.BigDecimal;

public record OrderActionResponse(
        OrderDto order,
        String message,
        BigDecimal availableCash,
        BigDecimal reservedCash) {

    public static OrderActionResponse of(OrderDto order, String message, BigDecimal cash, BigDecimal reserved) {
        return new OrderActionResponse(order, message, cash, reserved);
    }
}

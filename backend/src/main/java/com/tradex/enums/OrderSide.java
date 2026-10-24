package com.tradex.enums;

/** Direction of an order or trade. */
public enum OrderSide {
    BUY,
    SELL;

    public OrderSide opposite() {
        return this == BUY ? SELL : BUY;
    }
}

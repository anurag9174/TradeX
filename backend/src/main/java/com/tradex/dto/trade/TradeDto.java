package com.tradex.dto.trade;

import com.tradex.enums.OrderSide;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeDto(
        Long id,
        String tradeRef,
        String orderRef,
        String symbol,
        String instrumentName,
        String exchange,
        /** Quote currency of the instrument; {@code price} is printed in it. */
        String currency,
        /** Currency used by cash, charges, P&L and other account-level amounts. */
        String accountCurrency,

        OrderSide side,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal grossAmount,
        BigDecimal charges,
        BigDecimal netAmount,
        BigDecimal costBasis,

        /** Non-zero only for sells: realised P&L booked by this fill. */
        BigDecimal realizedPnl,
        /** realised pnl as a percentage of the cost basis released by this fill. */
        BigDecimal realizedPnlPercent,

        Instant executedAt) {
}

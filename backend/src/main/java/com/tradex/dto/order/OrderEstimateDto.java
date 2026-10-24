package com.tradex.dto.order;

import java.math.BigDecimal;
import java.util.List;

/** Result of a pre-trade estimate. Purely informational; the engine re-derives everything. */
public record OrderEstimateDto(
        String symbol,
        String side,
        String orderType,
        BigDecimal quantity,

        BigDecimal referencePrice,
        BigDecimal assumedExecutionPrice,
        BigDecimal orderValue,

        ChargesBreakdownDto charges,
        /** Buys: orderValue + charges. Sells: orderValue - charges. */
        BigDecimal totalCost,
        BigDecimal netProceeds,

        BigDecimal availableCash,
        BigDecimal reservedCash,
        boolean sufficientFunds,
        BigDecimal shortfallBy,

        BigDecimal heldQuantity,
        boolean sufficientHoldings,

        List<String> warnings,

        /** Quote currency of the instrument; {@code assumedExecutionPrice} is printed in it. */
        String priceCurrency,
        /** Currency the cash/P&amp;L amounts are held in (the ledger currency). */
        String ledgerCurrency,
        /** Ledger units per one unit of {@code priceCurrency}; 1 when no conversion applies. */
        BigDecimal fxRate) {
}

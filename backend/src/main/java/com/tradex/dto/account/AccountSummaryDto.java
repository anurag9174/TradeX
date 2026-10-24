package com.tradex.dto.account;

import java.math.BigDecimal;

/**
 * The dashboard's financial summary, derived server-side from the ledger, holdings and trades.
 * Every field is derived server-side from the ledger, holdings and executed trades.
 */
public record AccountSummaryDto(
        String currency,
        /** Non-null when some positions are priced in a currency other than {@link #currency}. */
        String fxNote,
        /** Card 1 */
        BigDecimal availableCash,
        BigDecimal initialCapital,
        /** Card 2 */
        BigDecimal portfolioValue,
        /** Card 3 - cost of everything currently held */
        BigDecimal totalInvestment,
        /** Card 4 */
        BigDecimal unrealizedPnl,
        /** Card 5 */
        BigDecimal realizedPnl,
        /** Card 6 */
        BigDecimal totalReturnPercent,
        /** Card 7 */
        int openPositions,
        /** Card 8 */
        BigDecimal todaysPnl,
        BigDecimal totalReturn,
        BigDecimal investedValue,
        BigDecimal holdingsValue,
        BigDecimal reservedCash,
        int openOrders,
        int dayTradeCount,
        BigDecimal todaysCharges) {
}

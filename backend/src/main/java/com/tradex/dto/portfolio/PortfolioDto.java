package com.tradex.dto.portfolio;

import com.tradex.enums.MarketDataSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Whole-portfolio snapshot: the cash split, aggregates and every open position. */
public record PortfolioDto(
        String accountNumber,
        String currency,
        BigDecimal initialBalance,

        BigDecimal availableCash,
        BigDecimal reservedCash,
        BigDecimal totalCash,

        BigDecimal investedValue,
        BigDecimal marketValue,
        BigDecimal portfolioValue,

        BigDecimal unrealizedPnl,
        BigDecimal unrealizedPnlPercent,
        BigDecimal realizedPnl,
        BigDecimal totalReturn,
        BigDecimal totalReturnPercent,
        BigDecimal todaysPnl,
        /** Non-null when some positions are priced in a currency other than {@link #currency}. */
        String fxNote,

        int positionCount,
        int openOrderCount,

        List<HoldingDto> holdings,
        List<AllocationSlice> allocation,

        MarketDataSource dataSource,
        String dataSourceLabel,
        Instant asOf) {

    public record AllocationSlice(String symbol, String name, BigDecimal marketValue, BigDecimal percent) {
    }
}

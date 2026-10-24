package com.tradex.dto.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Equity curve and period statistics for the Analytics page. */
public record PerformanceDto(
        String accountNumber,
        String currency,
        LocalDate from,
        LocalDate to,

        BigDecimal startingValue,
        BigDecimal endingValue,
        BigDecimal netChange,
        BigDecimal netChangePercent,

        BigDecimal realizedPnl,
        BigDecimal unrealizedPnl,
        BigDecimal totalCharges,
        BigDecimal investedCapital,

        int tradeCount,
        int winningTrades,
        int losingTrades,
        BigDecimal winRate,
        BigDecimal averageWin,
        BigDecimal averageLoss,
        BigDecimal profitFactor,
        BigDecimal bestTrade,
        BigDecimal worstTrade,

        BigDecimal maxDrawdown,
        BigDecimal maxDrawdownPercent,

        List<EquityPoint> equityCurve,
        List<DailyPnlPoint> dailyPnl,
        List<InstrumentPnl> byInstrument) {

    public record EquityPoint(LocalDate date, BigDecimal portfolioValue, BigDecimal cash, BigDecimal invested, BigDecimal realizedPnl) {
    }

    public record DailyPnlPoint(LocalDate date, BigDecimal realizedPnl, BigDecimal charges, BigDecimal netPnl, int tradeCount) {
    }

    public record InstrumentPnl(String symbol, String name, BigDecimal realizedPnl, int tradeCount, BigDecimal charges) {
    }
}

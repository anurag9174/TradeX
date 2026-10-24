package com.tradex.trading;

import com.tradex.config.TradingProperties;
import com.tradex.dto.order.ChargesBreakdownDto;
import com.tradex.enums.OrderSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The charge table mirrors the shape of an Indian discount-broker tariff. Both the pre-trade
 * estimate and the executed fill call this exact calculator, so these numbers are what a user is
 * quoted and what a user pays.
 */
class ChargesCalculatorTest {

    private final ChargesCalculator calculator = new ChargesCalculator(new TradingProperties());

    @Test
    void buyChargesCapBrokerageAndIncludeStampDutyButNotDp() {
        ChargesBreakdownDto buy = calculator.calculate(new BigDecimal("100000.00"), OrderSide.BUY);

        // 0.03% of 1,00,000 = 30.00 -> capped at 20.00
        assertThat(buy.brokerage()).isEqualByComparingTo("20.00");
        assertThat(buy.exchangeTransactionCharge()).isEqualByComparingTo("3.25");
        assertThat(buy.sebiTurnoverCharge()).isEqualByComparingTo("0.10");
        // 18% of (20.00 + 3.25 + 0.10) = 4.203 -> 4.20 at one-paisa granularity
        assertThat(buy.gst()).isEqualByComparingTo("4.20");
        assertThat(buy.stampDuty()).isEqualByComparingTo("15.00");
        assertThat(buy.dpCharges()).isEqualByComparingTo("0.00");
        assertThat(buy.total()).isEqualByComparingTo("42.55");
    }

    @Test
    void sellChargesDropStampDutyAndAddTheFlatDpCharge() {
        ChargesBreakdownDto sell = calculator.calculate(new BigDecimal("100000.00"), OrderSide.SELL);

        assertThat(sell.brokerage()).isEqualByComparingTo("20.00");
        assertThat(sell.stampDuty()).isEqualByComparingTo("0.00");
        assertThat(sell.dpCharges()).isEqualByComparingTo("13.50");
        assertThat(sell.total()).isEqualByComparingTo("41.05");
    }

    @Test
    void smallTurnoversAreRoundedToPaise() {
        ChargesBreakdownDto buy = calculator.calculate(new BigDecimal("10000.00"), OrderSide.BUY);

        assertThat(buy.brokerage()).isEqualByComparingTo("3.00");   // below the cap
        assertThat(buy.exchangeTransactionCharge()).isEqualByComparingTo("0.33"); // 0.325 rounded up
        assertThat(buy.sebiTurnoverCharge()).isEqualByComparingTo("0.01");
        assertThat(buy.gst()).isEqualByComparingTo("0.60");
        assertThat(buy.stampDuty()).isEqualByComparingTo("1.50");
        assertThat(buy.total()).isEqualByComparingTo("5.44");
    }

    @Test
    void sellSideCostsMoreThanBuySideAtSmallSizeBecauseOfTheDpCharge() {
        BigDecimal turnover = new BigDecimal("10000.00");
        assertThat(calculator.total(turnover, OrderSide.SELL))
                .isGreaterThan(calculator.total(turnover, OrderSide.BUY));
    }

    @Test
    void nonPositiveTurnoverOrNullSideIsFree() {
        assertThat(calculator.calculate(BigDecimal.ZERO, OrderSide.BUY).total())
                .isEqualByComparingTo("0");
        assertThat(calculator.calculate(new BigDecimal("-5"), OrderSide.BUY).total())
                .isEqualByComparingTo("0");
        assertThat(calculator.calculate(new BigDecimal("100"), null).total())
                .isEqualByComparingTo("0");
    }
}

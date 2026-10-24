package com.tradex.trading;

import com.tradex.config.TradingProperties;
import com.tradex.dto.order.ChargesBreakdownDto;
import com.tradex.enums.OrderSide;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Deterministic model of Indian discount-broker transaction charges.
 *
 * <p>Both the pre-trade estimate and the executed fill call this exact method, so what the user
 * is quoted is what the user is charged.</p>
 *
 * <pre>
 *   brokerage            = min(turnover * brokeragePercent / 100, brokerageCap)
 *   exchange txn charge  = turnover * exchangeTxnPercent / 100
 *   SEBI turnover fee    = turnover * sebiPercent / 100
 *   GST                  = (brokerage + exchange txn + SEBI) * gstPercent / 100
 *   stamp duty           = turnover * stampDutyPercent / 100   (BUY only)
 *   DP charge            = flat per SELL execution
 * </pre>
 */
@Component
public class ChargesCalculator {

    private final TradingProperties properties;

    public ChargesCalculator(TradingProperties properties) {
        this.properties = properties;
    }

    /**
     * @param turnover executed or assumed order value (quantity * price), always positive
     * @param side     BUY or SELL; drives one-sided charges
     */
    public ChargesBreakdownDto calculate(BigDecimal turnover, OrderSide side) {
        if (turnover == null || turnover.signum() <= 0 || side == null) {
            return ChargesBreakdownDto.zero();
        }
        TradingProperties.Charges cfg = properties.getCharges();
        BigDecimal step = cfg.getRoundingStep();

        BigDecimal brokerage = percent(turnover, cfg.getBrokeragePercent());
        if (brokerage.compareTo(cfg.getBrokerageCap()) > 0) {
            brokerage = cfg.getBrokerageCap();
        }
        brokerage = round(brokerage, step);

        BigDecimal exchange = round(percent(turnover, cfg.getExchangeTxnPercent()), step);
        BigDecimal sebi = round(percent(turnover, cfg.getSebiPercent()), step);

        BigDecimal gstBase = brokerage.add(exchange).add(sebi);
        BigDecimal gst = round(percent(gstBase, cfg.getGstPercent()), step);

        BigDecimal stampDuty = side == OrderSide.BUY
                ? round(percent(turnover, cfg.getStampDutyPercent()), step)
                : BigDecimal.ZERO.setScale(step.scale(), RoundingMode.HALF_UP);

        BigDecimal dp = side == OrderSide.SELL
                ? round(cfg.getDpChargePerSell(), step)
                : BigDecimal.ZERO.setScale(step.scale(), RoundingMode.HALF_UP);

        BigDecimal total = round(brokerage.add(exchange).add(sebi).add(gst).add(stampDuty).add(dp), step);

        return new ChargesBreakdownDto(brokerage, exchange, sebi, gst, stampDuty, dp, Decimals.money(total));
    }

    /** Total charge for the given turnover. */
    public BigDecimal total(BigDecimal turnover, OrderSide side) {
        return calculate(turnover, side).total();
    }

    private static BigDecimal percent(BigDecimal base, BigDecimal percent) {
        return base.multiply(percent, Decimals.MC).divide(Decimals.HUNDRED, Decimals.MC);
    }

    private static BigDecimal round(BigDecimal value, BigDecimal step) {
        if (step == null || step.signum() <= 0) {
            return Decimals.money(value);
        }
        return value.divide(step, 0, RoundingMode.HALF_UP).multiply(step);
    }
}

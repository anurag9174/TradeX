package com.tradex.trading;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Central place for every rounding rule in the platform.
 *
 * <p>Money is always stored and compared with {@code BigDecimal}, and every intermediate
 * calculation goes through here so a value can never differ between two code paths.</p>
 */
public final class Decimals {

    private Decimals() {
    }

    /** Working precision used for division-heavy maths (indicators, ratios). */
    public static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);

    /** Scale used for all currency amounts. */
    public static final int MONEY_SCALE = 4;

    /** Scale used for quantities. */
    public static final int QTY_SCALE = 6;

    /** Scale used for internal price maths. */
    public static final int PRICE_SCALE = 6;

    /** Scale exposed in the API / UI. */
    public static final int DISPLAY_MONEY_SCALE = 2;

    public static final BigDecimal HUNDRED = new BigDecimal("100");

    /** Normalises an amount to {@link #MONEY_SCALE} with HALF_UP rounding. */
    public static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal zeroMoney() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Normalises a quantity to {@link #QTY_SCALE}. */
    public static BigDecimal qty(BigDecimal value) {
        return value == null ? null : value.setScale(QTY_SCALE, RoundingMode.DOWN);
    }

    /** Multiplies two values and normalises the product to money scale. */
    public static BigDecimal multiply(BigDecimal a, BigDecimal b) {
        return money(a.multiply(b, MC));
    }

    /** {@code a / b} guarded against a zero denominator. */
    public static BigDecimal divide(BigDecimal a, BigDecimal b) {
        if (b == null || b.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return a.divide(b, MC);
    }

    /** Percentage change from {@code from} to {@code to}. Returns zero when {@code from} is zero. */
    public static BigDecimal percentChange(BigDecimal from, BigDecimal to) {
        if (from == null || to == null || from.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return to.subtract(from).multiply(HUNDRED, MC).divide(from, MC);
    }

    /** Rounds a price down/up to the instrument's tick grid. */
    public static BigDecimal toTick(BigDecimal price, BigDecimal tickSize) {
        if (price == null) {
            return null;
        }
        if (tickSize == null || tickSize.signum() <= 0) {
            return price.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal steps = price.divide(tickSize, 0, RoundingMode.HALF_UP);
        BigDecimal snapped = steps.multiply(tickSize);
        int scale = Math.max(tickSize.scale(), MONEY_SCALE);
        return snapped.setScale(Math.min(scale, 8), RoundingMode.HALF_UP);
    }

    /** Rounds a price to the tick grid, never below one tick. */
    public static BigDecimal toTickFloor(BigDecimal price, BigDecimal tickSize) {
        if (price == null) {
            return null;
        }
        if (tickSize == null || tickSize.signum() <= 0) {
            return price;
        }
        BigDecimal steps = price.divide(tickSize, 0, RoundingMode.FLOOR);
        return steps.multiply(tickSize);
    }

    /** Applies a basis-point adjustment (positive = more expensive). */
    public static BigDecimal applyBps(BigDecimal value, int bps) {
        if (value == null || bps == 0) {
            return value;
        }
        BigDecimal factor = BigDecimal.ONE.add(BigDecimal.valueOf(bps).divide(BigDecimal.valueOf(10000), MC), MC);
        return value.multiply(factor, MC);
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    public static boolean isZero(BigDecimal value) {
        return value == null || value.signum() == 0;
    }

    public static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public static BigDecimal max(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}

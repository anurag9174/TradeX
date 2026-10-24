package com.tradex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Trading-engine configuration.
 *
 * <p>Every value here is a <em>simulation</em> parameter. The charge table mirrors the shape of a
 * real Indian discount-broker tariff so that practising P&amp;L is realistic, but no money ever
 * moves.</p>
 */
@ConfigurationProperties(prefix = "tradex.trading")
public class TradingProperties {

    /** Default virtual cash granted when signup omits its optional capital. */
    private BigDecimal initialBalance = new BigDecimal("10000.00");

    private BigDecimal minimumInitialBalance = new BigDecimal("100.00");

    private BigDecimal maximumInitialBalance = new BigDecimal("1000000.00");

    /** Legacy ledger currency retained for existing INR accounts. */
    private String currency = "INR";

    /** Currency assigned to newly created or explicitly reset accounts. */
    private String defaultAccountCurrency = "USD";

    /**
     * Ledger conversion rates for instruments quoted outside {@link #currency}: currency code ->
     * base-currency units per one unit of that currency, for example {@code USD: 88.50}.
     *
     * <p>This is a <em>nominal</em> simulation parameter, not a live FX feed. A USD-quoted
     * instrument (Delta Exchange perpetuals) shows its real price in USD, while every cash, P&amp;L
     * and risk amount stays denominated in {@link #currency} and is converted at this rate.</p>
     */
    private Map<String, BigDecimal> fxRates = new LinkedHashMap<>(Map.of("USD", new BigDecimal("88.50")));

    private final Charges charges = new Charges();

    /** Extra adverse ticks applied to market-order fills, in basis points (1 bp = 0.01%). */
    private int marketOrderSlippageBps = 0;

    /**
     * When true, a resting SELL limit order also blocks the underlying quantity from being
     * sold twice. Buy reservations always lock cash.
     */
    private boolean reserveSellQuantities = true;

    /** Allow the platform to trade 24x7 even when a real venue would be closed. */
    private boolean alwaysOpen = true;

    /** IANA zone used for "today", daily P&L and daily-loss-limit boundaries. */
    private String tradingZone = "Asia/Kolkata";

    /**
     * When true, the matching engine converts a breach of a position's attached stop-loss or target
     * into a market exit. This is what makes stop-loss planning behavioural rather than cosmetic.
     */
    private boolean autoTriggerAttachedLevels = true;

    /**
     * Protective exits are risk-reducing, so they are allowed to fire even when the daily loss limit
     * is already breached. Set to false to make the loss guard absolute.
     */
    private boolean allowProtectiveExitsWhenBlocked = true;

    public BigDecimal getInitialBalance() {
        return initialBalance;
    }

    public void setInitialBalance(BigDecimal initialBalance) {
        this.initialBalance = initialBalance;
    }

    public BigDecimal getMinimumInitialBalance() {
        return minimumInitialBalance;
    }

    public void setMinimumInitialBalance(BigDecimal minimumInitialBalance) {
        this.minimumInitialBalance = minimumInitialBalance;
    }

    public BigDecimal getMaximumInitialBalance() {
        return maximumInitialBalance;
    }

    public void setMaximumInitialBalance(BigDecimal maximumInitialBalance) {
        this.maximumInitialBalance = maximumInitialBalance;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getDefaultAccountCurrency() {
        return defaultAccountCurrency;
    }

    public void setDefaultAccountCurrency(String defaultAccountCurrency) {
        this.defaultAccountCurrency = defaultAccountCurrency;
    }

    public Map<String, BigDecimal> getFxRates() {
        return fxRates;
    }

    public void setFxRates(Map<String, BigDecimal> fxRates) {
        Map<String, BigDecimal> cleaned = new LinkedHashMap<>();
        if (fxRates != null) {
            fxRates.forEach((code, rate) -> {
                if (code == null || code.isBlank() || rate == null || rate.signum() <= 0) {
                    return;
                }
                cleaned.put(code.trim().toUpperCase(Locale.ROOT), rate);
            });
        }
        this.fxRates = cleaned;
    }

    public Charges getCharges() {
        return charges;
    }

    public int getMarketOrderSlippageBps() {
        return marketOrderSlippageBps;
    }

    public void setMarketOrderSlippageBps(int marketOrderSlippageBps) {
        this.marketOrderSlippageBps = marketOrderSlippageBps;
    }

    public boolean isReserveSellQuantities() {
        return reserveSellQuantities;
    }

    public void setReserveSellQuantities(boolean reserveSellQuantities) {
        this.reserveSellQuantities = reserveSellQuantities;
    }

    public boolean isAlwaysOpen() {
        return alwaysOpen;
    }

    public void setAlwaysOpen(boolean alwaysOpen) {
        this.alwaysOpen = alwaysOpen;
    }

    public String getTradingZone() {
        return tradingZone;
    }

    public void setTradingZone(String tradingZone) {
        this.tradingZone = tradingZone;
    }

    public boolean isAutoTriggerAttachedLevels() {
        return autoTriggerAttachedLevels;
    }

    public void setAutoTriggerAttachedLevels(boolean autoTriggerAttachedLevels) {
        this.autoTriggerAttachedLevels = autoTriggerAttachedLevels;
    }

    public boolean isAllowProtectiveExitsWhenBlocked() {
        return allowProtectiveExitsWhenBlocked;
    }

    public void setAllowProtectiveExitsWhenBlocked(boolean allowProtectiveExitsWhenBlocked) {
        this.allowProtectiveExitsWhenBlocked = allowProtectiveExitsWhenBlocked;
    }

    /** Simulated statutory + brokerage charge table. All rates are percentages unless stated. */
    public static class Charges {

        /** Brokerage as a percentage of turnover (default 0.03% - typical discount-broker delivery). */
        private BigDecimal brokeragePercent = new BigDecimal("0.03");

        /** Brokerage is capped at this amount per executed order. */
        private BigDecimal brokerageCap = new BigDecimal("20.00");

        /** Exchange transaction charge on equity delivery, percentage of turnover. */
        private BigDecimal exchangeTxnPercent = new BigDecimal("0.00325");

        /** SEBI turnover fee, percentage of turnover. */
        private BigDecimal sebiPercent = new BigDecimal("0.0001");

        /** GST applied on (brokerage + exchange charge + SEBI fee). */
        private BigDecimal gstPercent = new BigDecimal("18.00");

        /** Stamp duty, percentage of turnover. Charged on BUY side only. */
        private BigDecimal stampDutyPercent = new BigDecimal("0.015");

        /** Flat depository charge on every SELL execution. */
        private BigDecimal dpChargePerSell = new BigDecimal("13.50");

        /** Rounding granularity for rupee amounts (1 paisa). */
        private BigDecimal roundingStep = new BigDecimal("0.01");

        public BigDecimal getBrokeragePercent() {
            return brokeragePercent;
        }

        public void setBrokeragePercent(BigDecimal brokeragePercent) {
            this.brokeragePercent = brokeragePercent;
        }

        public BigDecimal getBrokerageCap() {
            return brokerageCap;
        }

        public void setBrokerageCap(BigDecimal brokerageCap) {
            this.brokerageCap = brokerageCap;
        }

        public BigDecimal getExchangeTxnPercent() {
            return exchangeTxnPercent;
        }

        public void setExchangeTxnPercent(BigDecimal exchangeTxnPercent) {
            this.exchangeTxnPercent = exchangeTxnPercent;
        }

        public BigDecimal getSebiPercent() {
            return sebiPercent;
        }

        public void setSebiPercent(BigDecimal sebiPercent) {
            this.sebiPercent = sebiPercent;
        }

        public BigDecimal getGstPercent() {
            return gstPercent;
        }

        public void setGstPercent(BigDecimal gstPercent) {
            this.gstPercent = gstPercent;
        }

        public BigDecimal getStampDutyPercent() {
            return stampDutyPercent;
        }

        public void setStampDutyPercent(BigDecimal stampDutyPercent) {
            this.stampDutyPercent = stampDutyPercent;
        }

        public BigDecimal getDpChargePerSell() {
            return dpChargePerSell;
        }

        public void setDpChargePerSell(BigDecimal dpChargePerSell) {
            this.dpChargePerSell = dpChargePerSell;
        }

        public BigDecimal getRoundingStep() {
            return roundingStep;
        }

        public void setRoundingStep(BigDecimal roundingStep) {
            this.roundingStep = roundingStep;
        }
    }
}

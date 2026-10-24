package com.tradex.trading;

import com.tradex.config.TradingProperties;
import com.tradex.entity.Instrument;
import com.tradex.exception.BadRequestException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Converts between an instrument's own quote currency and an account's currency.
 *
 * <p>Prices, average costs, stops and targets always stay in the instrument's quote currency -
 * that is the number the market prints. Every cash, P&amp;L, charge and risk amount stays in the
 * account currency. New USD accounts never use an implicit exchange rate: conversion-requiring
 * orders are refused until a reliable FX source is explicitly available.</p>
 *
 * <p>The rate is an operator-set simulation parameter (for example 88.50 INR per USD for Delta
 * Exchange perpetuals). It is deliberately <em>not</em> a live FX feed: an FX quote would be one
 * more real market price the paper engine claims to know.</p>
 */
@Service
public class CurrencyConverter {

    private final TradingProperties properties;

    public CurrencyConverter(TradingProperties properties) {
        this.properties = properties;
    }

    /** Legacy ledger currency used by accounts created before per-account currency was introduced. */
    public String ledgerCurrency() {
        return properties.getCurrency() == null ? "INR" : properties.getCurrency().toUpperCase(Locale.ROOT);
    }

    /** Base-account units per one unit of the instrument's quote currency. */
    public BigDecimal factor(Instrument instrument) {
        return factor(instrument, ledgerCurrency());
    }

    public BigDecimal factor(Instrument instrument, String accountCurrency) {
        if (instrument == null || instrument.getCurrency() == null || instrument.getCurrency().isBlank()) {
            return BigDecimal.ONE;
        }
        String quote = normalize(instrument.getCurrency());
        String settlement = normalize(instrument.getSettlementCurrency());
        if (!quote.equals(settlement)) {
            throw new BadRequestException("UNSUPPORTED_CONTRACT_SETTLEMENT",
                    "The " + instrument.getSymbol() + " contract settles in " + settlement
                            + " while its price is quoted in " + quote
                            + "; its contract-specific conversion is not configured.");
        }
        String code = settlement;
        String base = normalize(accountCurrency);
        if (code.equals(base)) {
            return BigDecimal.ONE;
        }
        if (base.equals(ledgerCurrency())) {
            BigDecimal legacyRate = properties.getFxRates().get(code);
            if (legacyRate != null && legacyRate.signum() > 0) {
                return legacyRate;
            }
        }
        throw new BadRequestException("CURRENCY_CONVERSION_UNAVAILABLE",
                "Trading " + instrument.getSymbol() + " requires " + code + " to " + base
                        + " conversion, but no reliable FX source is configured.");
    }

    /** Converts a price-space amount using the legacy ledger currency. */
    public BigDecimal toLedger(Instrument instrument, BigDecimal priceSpaceAmount) {
        return toLedger(instrument, priceSpaceAmount, ledgerCurrency());
    }

    public BigDecimal toLedger(Instrument instrument, BigDecimal priceSpaceAmount, String accountCurrency) {
        if (priceSpaceAmount == null) {
            return null;
        }
        return Decimals.money(priceSpaceAmount.multiply(factor(instrument, accountCurrency), Decimals.MC));
    }

    /** True when amounts for this instrument need an FX conversion under legacy behavior. */
    public boolean converts(Instrument instrument) {
        return factor(instrument).compareTo(BigDecimal.ONE) != 0;
    }

    public boolean hasSameCurrency(Instrument instrument, String accountCurrency) {
        return instrument != null
                && normalize(instrument.getCurrency()).equals(normalize(accountCurrency))
                && normalize(instrument.getSettlementCurrency()).equals(normalize(accountCurrency));
    }

    public void requireSameCurrencyForTrading(Instrument instrument, String accountCurrency) {
        if (!hasSameCurrency(instrument, accountCurrency)) {
            String quoteCurrency = instrument.getCurrency() == null ? "unknown" : instrument.getCurrency();
            throw new BadRequestException("CURRENCY_CONVERSION_UNAVAILABLE",
                    "Orders in " + quoteCurrency + " quote / " + instrument.getSettlementCurrency()
                            + " settlement instruments are disabled for " + normalize(accountCurrency)
                            + " accounts until a reliable FX and contract-settlement source is configured.");
        }
    }

    /**
     * A human-readable note describing the nominal conversions that apply to the given instruments,
     * or {@code null} when every amount is already in the ledger currency.
     */
    public String ledgerNote(Collection<Instrument> instruments) {
        return ledgerNote(instruments, ledgerCurrency());
    }

    public String ledgerNote(Collection<Instrument> instruments, String accountCurrency) {
        Map<String, BigDecimal> needed = new LinkedHashMap<>();
        if (instruments != null) {
            for (Instrument instrument : instruments) {
                if (instrument == null || hasSameCurrency(instrument, accountCurrency)) {
                    continue;
                }
                needed.putIfAbsent(instrument.getCurrency().trim().toUpperCase(Locale.ROOT),
                        factor(instrument, accountCurrency));
            }
        }
        if (needed.isEmpty()) {
            return null;
        }
        StringBuilder note = new StringBuilder("Prices are shown in each instrument's own currency; cash, P&L and "
                + "risk amounts are held in ").append(normalize(accountCurrency)).append(" at the legacy nominal rate ");
        boolean first = true;
        for (Map.Entry<String, BigDecimal> entry : needed.entrySet()) {
            if (!first) {
                note.append(", ");
            }
            note.append("1 ").append(entry.getKey()).append(" = ")
                    .append(entry.getValue().stripTrailingZeros().toPlainString()).append(' ')
                    .append(normalize(accountCurrency));
            first = false;
        }
        note.append(" (operator-set; no live FX feed is modelled).");
        return note.toString();
    }

    private static String normalize(String currency) {
        return currency == null || currency.isBlank() ? "INR" : currency.trim().toUpperCase(Locale.ROOT);
    }
}

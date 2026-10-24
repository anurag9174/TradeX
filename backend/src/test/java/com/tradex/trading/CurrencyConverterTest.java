package com.tradex.trading;

import com.tradex.config.TradingProperties;
import com.tradex.entity.Instrument;
import com.tradex.enums.AssetClass;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single conversion point between an instrument's quote currency and the ledger currency.
 *
 * <p>Delta Exchange perpetuals are quoted in USD while the ledger is INR: prices must stay in
 * dollars and every money amount must be converted. These tests pin the boundaries of that
 * contract, including the deliberate fallback of "no invented rate".</p>
 */
class CurrencyConverterTest {

    private static Instrument instrument(String symbol, String currency) {
        Instrument instrument = new Instrument();
        instrument.setId(1L);
        instrument.setSymbol(symbol);
        instrument.setName(symbol);
        instrument.setExchange("DELTA");
        instrument.setAssetClass(AssetClass.CRYPTO);
        instrument.setCurrency(currency);
        return instrument;
    }

    private static CurrencyConverter converter(BigDecimal usdRate) {
        TradingProperties properties = new TradingProperties();
        properties.setFxRates(Map.of("USD", usdRate));
        return new CurrencyConverter(properties);
    }

    private static CurrencyConverter converterWithoutRates() {
        TradingProperties properties = new TradingProperties();
        properties.setFxRates(Map.of());
        return new CurrencyConverter(properties);
    }

    @Test
    void instrumentsQuotedInTheLedgerCurrencyAreUnchanged() {
        CurrencyConverter converter = converter(new BigDecimal("88.50"));
        Instrument inr = instrument("RELIANCE", "INR");

        assertThat(converter.ledgerCurrency()).isEqualTo("INR");
        assertThat(converter.factor(inr)).isEqualByComparingTo("1");
        assertThat(converter.toLedger(inr, new BigDecimal("1290.125"))).isEqualByComparingTo("1290.1250");
        assertThat(converter.converts(inr)).isFalse();
        assertThat(converter.ledgerNote(List.of(inr))).isNull();
    }

    @Test
    void usdInstrumentsConvertAtTheNominalConfiguredRate() {
        CurrencyConverter converter = converter(new BigDecimal("88.50"));
        Instrument usd = instrument("BTCUSD", "USD");

        assertThat(converter.factor(usd)).isEqualByComparingTo("88.50");
        // 0.01 BTC at $82,000 is $820 of turnover -> Rs 72,570 in the ledger.
        assertThat(converter.toLedger(usd, new BigDecimal("820"))).isEqualByComparingTo("72570.0000");
        assertThat(converter.converts(usd)).isTrue();

        String note = converter.ledgerNote(List.of(usd));
        assertThat(note).contains("1 USD = 88.5 INR");
        assertThat(note).contains("nominal");
    }

    @Test
    void missingRateFallsBackToIdentityInsteadOfInventingARate() {
        CurrencyConverter converter = converterWithoutRates();
        Instrument usd = instrument("BTCUSD", "USD");

        assertThat(converter.factor(usd)).isEqualByComparingTo("1");
        assertThat(converter.toLedger(usd, new BigDecimal("820"))).isEqualByComparingTo("820");
        // No conversion means no confusing FX note either.
        assertThat(converter.ledgerNote(List.of(usd))).isNull();
    }

    @Test
    void blankOrNullCurrencyAndInstrumentAreTreatedAsIdentity() {
        CurrencyConverter converter = converter(new BigDecimal("88.50"));
        Instrument blank = instrument("MYSTERY", " ");

        assertThat(converter.factor(null)).isEqualByComparingTo("1");
        assertThat(converter.factor(blank)).isEqualByComparingTo("1");
        assertThat(converter.toLedger(null, new BigDecimal("10"))).isEqualByComparingTo("10");
        assertThat(converter.toLedger(blank, null)).isNull();
    }

    @Test
    void nonPositiveRatesAreDroppedByThePropertiesSetter() {
        TradingProperties properties = new TradingProperties();
        properties.setFxRates(Map.of(
                "USD", BigDecimal.ZERO,
                "eur", new BigDecimal("92.25")));

        assertThat(properties.getFxRates()).containsOnlyKeys("EUR");
        assertThat(properties.getFxRates().get("EUR")).isEqualByComparingTo("92.25");
    }

    @Test
    void ratesAreNormalisedToUpperCasedTrimmedKeys() {
        TradingProperties properties = new TradingProperties();
        properties.setFxRates(Map.of(" usd ", new BigDecimal("83.10")));

        assertThat(properties.getFxRates()).containsOnlyKeys("USD");
    }

    @Test
    void ledgerNoteIsNullWhenNothingNeedsConversion() {
        CurrencyConverter converter = converter(new BigDecimal("88.50"));

        assertThat(converter.ledgerNote(List.of(instrument("RELIANCE", "INR")))).isNull();
        assertThat(converter.ledgerNote(List.of())).isNull();
        assertThat(converter.ledgerNote(null)).isNull();
    }
}

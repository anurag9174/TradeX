package com.tradex.marketdata;

import com.tradex.config.MarketDataProperties;
import com.tradex.entity.Instrument;
import com.tradex.enums.AssetClass;
import com.tradex.enums.MarketDataSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Provider selection, mode resolution and the fail-fast contract of {@code mode=live}.
 *
 * <p>The registry must never silently serve simulated prices in a live session, and it must let the
 * key-less Delta provider go live without an API key.</p>
 */
class MarketDataRegistryTest {

    private final SimulatedMarketDataProvider simulated = mock(SimulatedMarketDataProvider.class);
    private final TwelveDataMarketDataProvider twelveData = mock(TwelveDataMarketDataProvider.class);
    private final DeltaExchangeMarketDataProvider delta = mock(DeltaExchangeMarketDataProvider.class);

    MarketDataRegistryTest() {
        when(simulated.id()).thenReturn("SIMULATED");
        when(simulated.displayName()).thenReturn("TradeX simulated feed");
        when(simulated.source()).thenReturn(MarketDataSource.SIMULATED);
        when(twelveData.id()).thenReturn("TWELVEDATA");
        when(twelveData.displayName()).thenReturn("Twelve Data");
        when(twelveData.source()).thenReturn(MarketDataSource.LIVE);
        when(delta.id()).thenReturn("DELTA");
        when(delta.displayName()).thenReturn("Delta Exchange (live market data)");
        when(delta.source()).thenReturn(MarketDataSource.LIVE);
    }

    private MarketDataRegistry registry(String mode, String provider) {
        MarketDataProperties properties = new MarketDataProperties();
        properties.setMode(mode);
        properties.setProvider(provider);
        return new MarketDataRegistry(properties, simulated, twelveData, delta);
    }

    private static Instrument instrument() {
        Instrument instrument = new Instrument();
        instrument.setId(9L);
        instrument.setSymbol("BTCUSD");
        instrument.setName("Bitcoin / US Dollar Perpetual");
        instrument.setExchange("DELTA");
        instrument.setAssetClass(AssetClass.CRYPTO);
        instrument.setCurrency("USD");
        return instrument;
    }

    @Test
    void deltaGoesLiveInAutoModeWithoutAnyApiKey() {
        when(delta.isConfigured()).thenReturn(true);

        MarketDataRegistry registry = registry("auto", "delta");
        registry.initialise();

        assertThat(registry.isLiveMode()).isTrue();
        assertThat(registry.activeSource()).isEqualTo(MarketDataSource.LIVE);
        assertThat(registry.activeProviderId()).isEqualTo("DELTA");
        assertThat(registry.liveConfigured()).isTrue();
        assertThat(registry.providerFor(instrument())).isSameAs(delta);
        assertThat(registry.activeLabel()).contains("LIVE");
        assertThat(registry.diagnostics()).containsKeys("deltaStatus", "mode", "liveActive");
    }

    @Test
    void liveModeRefusesToStartWhenTheSelectedProviderIsNotConfigured() {
        when(twelveData.isConfigured()).thenReturn(false);

        MarketDataRegistry registry = registry("live", "twelvedata");

        assertThatThrownBy(registry::initialise)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start");
    }

    @Test
    void autoModeStaysSimulatedWhenTheProviderIsNotConfigured() {
        when(twelveData.isConfigured()).thenReturn(false);

        MarketDataRegistry registry = registry("auto", "twelvedata");
        registry.initialise();

        assertThat(registry.isLiveMode()).isFalse();
        assertThat(registry.activeProviderId()).isEqualTo("SIMULATED");
        assertThat(registry.providerFor(instrument())).isSameAs(simulated);
        assertThat(registry.activeLabel()).contains("SIMULATED");
    }

    @Test
    void autoModeGoesLiveForASelectableKeyedProviderOnceConfigured() {
        when(twelveData.isConfigured()).thenReturn(true);

        MarketDataRegistry registry = registry("auto", "twelvedata");
        registry.initialise();

        assertThat(registry.isLiveMode()).isTrue();
        assertThat(registry.providerFor(instrument())).isSameAs(twelveData);
    }

    @Test
    void simulatedModeIsHonouredEvenWhenAProviderIsConfigured() {
        when(delta.isConfigured()).thenReturn(true);

        MarketDataRegistry registry = registry("simulated", "delta");
        registry.initialise();

        assertThat(registry.isLiveMode()).isFalse();
        assertThat(registry.activeSource()).isEqualTo(MarketDataSource.SIMULATED);
        assertThat(registry.providerFor(instrument())).isSameAs(simulated);
    }

    @Test
    void unknownProviderIdsFailFastInsteadOfFallingBack() {
        MarketDataRegistry registry = registry("auto", "kucoin");

        assertThatThrownBy(registry::initialise)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown tradex.marketdata.provider");
    }

    @Test
    void providerIdAliasesResolveToTheSameAdapter() {
        when(delta.isConfigured()).thenReturn(true);

        assertThat(registry("auto", "delta-exchange").providerFor(instrument())).isNotNull();
        assertThat(registry("auto", "delta").live()).isSameAs(delta);
    }
}

package com.tradex.marketdata;

import com.tradex.entity.Instrument;
import com.tradex.entity.MarketCandle;
import com.tradex.enums.AssetClass;
import com.tradex.enums.MarketDataSource;
import com.tradex.enums.Timeframe;
import com.tradex.repository.MarketCandleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The honesty rules of the live candle path.
 *
 * <ol>
 *   <li>Only the provider's own bars are served - closed bars plus the exchange's forming bar.</li>
 *   <li>Provider outages fall back to stored <em>live</em> bars, never to generated ones.</li>
 *   <li>When nothing real exists, the chart is told so instead of being filled.</li>
 *   <li>The simulated path reads and writes only {@code source=SIMULATED} rows.</li>
 * </ol>
 */
class CandleServiceLiveTest {

    private static final Instant BUCKET = Instant.parse("2026-10-15T12:00:00Z");

    private MarketCandleRepository repository;
    private MarketDataRegistry registry;
    private CandleFactory candleFactory;
    private LiveCandleStore liveCandleStore;
    private MarketDataProvider provider;
    private CandleService candleService;
    private Instrument btcUsd;

    @BeforeEach
    void setUp() {
        repository = mock(MarketCandleRepository.class);
        registry = mock(MarketDataRegistry.class);
        candleFactory = mock(CandleFactory.class);
        liveCandleStore = mock(LiveCandleStore.class);
        provider = mock(MarketDataProvider.class);
        candleService = new CandleService(repository, registry, candleFactory, liveCandleStore);

        btcUsd = new Instrument();
        btcUsd.setId(7L);
        btcUsd.setSymbol("BTCUSD");
        btcUsd.setName("Bitcoin / US Dollar Perpetual");
        btcUsd.setExchange("DELTA");
        btcUsd.setAssetClass(AssetClass.CRYPTO);
        btcUsd.setCurrency("USD");
        btcUsd.setProviderSymbol("DELTA:BTCUSD");

        when(registry.providerFor(btcUsd)).thenReturn(provider);
        when(registry.unsupportedReason(btcUsd)).thenReturn("not mapped");
        when(provider.source()).thenReturn(MarketDataSource.LIVE);
        when(provider.live()).thenReturn(true);
        when(provider.supports(btcUsd)).thenReturn(true);
        when(provider.displayName()).thenReturn("Delta Exchange (live market data)");
        when(candleFactory.alignOpenTime(any(), any())).thenReturn(BUCKET);
    }

    private static CandleBar bar(Instant openTime, String close) {
        BigDecimal price = new BigDecimal(close);
        return new CandleBar(openTime, price, price, price, price, new BigDecimal("1.5"));
    }

    private static MarketCandle candle(Instant openTime, String close) {
        MarketCandle candle = new MarketCandle();
        candle.setId(1L);
        candle.setInstrumentId(7L);
        candle.setTimeframe(Timeframe.M5);
        candle.setOpenTime(openTime);
        candle.setOpen(new BigDecimal(close));
        candle.setHigh(new BigDecimal(close));
        candle.setLow(new BigDecimal(close));
        candle.setClose(new BigDecimal(close));
        candle.setVolume(new BigDecimal("2"));
        candle.setSource(MarketDataSource.LIVE);
        return candle;
    }

    @Test
    void servesTheExchangesOwnBarsAndStoresClosedCandlesOnly() {
        List<CandleBar> fetched = List.of(
                bar(BUCKET.minusSeconds(900), "81900"),
                bar(BUCKET.minusSeconds(600), "82000"),
                bar(BUCKET.minusSeconds(300), "82041.5"),
                bar(BUCKET, "82050")); // the forming bar: real, partial, still fine to display
        when(provider.candles(btcUsd, Timeframe.M5, 11)).thenReturn(fetched);

        CandleService.Series series = candleService.series(btcUsd, Timeframe.M5, 10);

        assertThat(series.bars()).hasSize(4);
        assertThat(series.bars().get(3).openTime()).isEqualTo(BUCKET);
        assertThat(series.bars().get(3).close()).isEqualByComparingTo("82050");
        assertThat(series.source()).isEqualTo(MarketDataSource.LIVE);
        assertThat(series.live()).isTrue();
        assertThat(series.synthesised()).isFalse();
        assertThat(series.note()).contains("Delta Exchange");
        assertThat(series.note()).doesNotContain("Simulated");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CandleBar>> stored = ArgumentCaptor.forClass(List.class);
        verify(liveCandleStore).store(eq(btcUsd), eq(Timeframe.M5), stored.capture());
        assertThat(stored.getValue()).hasSize(3);
        assertThat(stored.getValue()).allMatch(bar -> bar.openTime().isBefore(BUCKET));
    }

    @Test
    void providerOutageFallsBackToStoredLiveBarsAndNeverSynthesises() {
        when(provider.candles(btcUsd, Timeframe.M5, 11)).thenReturn(List.of());
        when(repository.findRecentBySourceDesc(eq(7L), eq(Timeframe.M5), eq(MarketDataSource.LIVE), any()))
                .thenReturn(List.of(
                        candle(BUCKET.minusSeconds(300), "82041.5"),   // newest first, as stored
                        candle(BUCKET.minusSeconds(600), "82000")));

        CandleService.Series series = candleService.series(btcUsd, Timeframe.M5, 10);

        assertThat(series.bars()).hasSize(2);
        assertThat(series.bars().get(0).openTime()).isEqualTo(BUCKET.minusSeconds(600));
        assertThat(series.note()).contains("not refreshing");
        assertThat(series.source()).isEqualTo(MarketDataSource.LIVE);
        verify(liveCandleStore, never()).store(any(), any(), any());
        verify(candleFactory, never()).liveBar(any(), any(), any(), any());
        verify(candleFactory, never()).closedBar(any(), any(), any());
    }

    @Test
    void withNothingRealToShowTheSeriesSaysSoInsteadOfInventingCandles() {
        when(provider.candles(btcUsd, Timeframe.M5, 11)).thenReturn(List.of());
        when(repository.findRecentBySourceDesc(eq(7L), eq(Timeframe.M5), eq(MarketDataSource.LIVE), any()))
                .thenReturn(List.of());

        CandleService.Series series = candleService.series(btcUsd, Timeframe.M5, 10);

        assertThat(series.bars()).isEmpty();
        assertThat(series.note()).contains("Nothing is generated");
        verify(candleFactory, never()).closedBar(any(), any(), any());
        verify(liveCandleStore, never()).store(any(), any(), any());
    }

    @Test
    void unsupportedInstrumentsAnswerWithTheProviderReason() {
        when(provider.supports(btcUsd)).thenReturn(false);

        CandleService.Series series = candleService.series(btcUsd, Timeframe.M5, 10);

        assertThat(series.bars()).isEmpty();
        assertThat(series.note()).isEqualTo("not mapped");
        verify(provider, never()).candles(any(), any(), anyInt());
    }

    @Test
    void simulatedPathReadsAndSynthesisesOnlySimulatedRows() {
        when(provider.live()).thenReturn(false);
        when(provider.source()).thenReturn(MarketDataSource.SIMULATED);
        when(candleFactory.liveBar(eq(btcUsd), eq(Timeframe.M5), eq(BUCKET), any()))
                .thenReturn(bar(BUCKET, "82500"));
        when(candleFactory.closedBar(eq(btcUsd), eq(Timeframe.M5), any(Instant.class)))
                .thenAnswer(call -> bar(call.getArgument(2), "82000"));
        when(repository.findByInstrumentIdAndTimeframeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                eq(7L), eq(Timeframe.M5), eq(MarketDataSource.SIMULATED), any(), any()))
                .thenReturn(List.of(
                        marketCandle(Timeframe.M5, MarketDataSource.SIMULATED, BUCKET.minusSeconds(600), "81800"),
                        marketCandle(Timeframe.M5, MarketDataSource.SIMULATED, BUCKET.minusSeconds(300), "81900")));

        CandleService.Series series = candleService.series(btcUsd, Timeframe.M5, 10);

        assertThat(series.source()).isEqualTo(MarketDataSource.SIMULATED);
        assertThat(series.live()).isFalse();
        assertThat(series.bars()).hasSize(10);
        assertThat(series.bars().get(9).openTime()).isEqualTo(BUCKET);
        assertThat(series.note()).contains("Simulated candles");
        verify(provider, never()).candles(any(), any(), anyInt());
        verify(repository, never()).findRecentBySourceDesc(any(), any(), any(), any());
        verify(repository, never())
                .findByInstrumentIdAndTimeframeAndOpenTimeBetweenOrderByOpenTimeAsc(any(), any(), any(), any());
    }

    private static MarketCandle marketCandle(Timeframe timeframe, MarketDataSource source,
                                             Instant openTime, String close) {
        MarketCandle candle = new MarketCandle();
        candle.setInstrumentId(7L);
        candle.setTimeframe(timeframe);
        candle.setOpenTime(openTime);
        candle.setOpen(new BigDecimal(close));
        candle.setHigh(new BigDecimal(close));
        candle.setLow(new BigDecimal(close));
        candle.setClose(new BigDecimal(close));
        candle.setVolume(new BigDecimal("3"));
        candle.setSource(source);
        return candle;
    }

    @Test
    void limitIsClampedToASaneRange() {
        when(provider.candles(eq(btcUsd), eq(Timeframe.M5), anyInt())).thenReturn(List.of());

        candleService.series(btcUsd, Timeframe.M5, 1);
        verify(provider).candles(btcUsd, Timeframe.M5, 11); // floor of 10, plus one probe bar

        candleService.series(btcUsd, Timeframe.M5, 100_000);
        verify(provider).candles(btcUsd, Timeframe.M5, 2001); // cap of 2000, plus one probe bar
    }

    @Test
    void aHugeLimitStillEndsWithTheExchangesFormingBar() {
        when(provider.candles(eq(btcUsd), eq(Timeframe.M5), anyInt())).thenReturn(List.of(
                bar(BUCKET.minusSeconds(10800), "81500"),
                bar(BUCKET, "82000")));

        CandleService.Series series = candleService.series(btcUsd, Timeframe.M5, 60_000);

        assertThat(series.bars()).isNotEmpty();
        assertThat(series.bars().get(series.bars().size() - 1).openTime()).isEqualTo(BUCKET);
    }
}

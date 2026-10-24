package com.tradex.marketdata;

import com.tradex.dto.market.CandleSeriesDto;
import com.tradex.dto.market.IndicatorSetDto;
import com.tradex.dto.market.MarketStatusDto;
import com.tradex.dto.market.QuoteDto;
import com.tradex.entity.Instrument;
import com.tradex.enums.MarketDataSource;
import com.tradex.enums.MarketStatus;
import com.tradex.enums.Timeframe;
import com.tradex.exception.MarketDataUnavailableException;
import com.tradex.service.InstrumentService;
import com.tradex.trading.Decimals;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single entry point for every price the platform uses.
 *
 * <p>Prices flow through here into three consumers: the quote REST endpoints, the WebSocket ticker,
 * and - critically - the trading engine, which asks for a quote at the moment it fills an order. All
 * three therefore see exactly the same number.</p>
 *
 * <p>When an instrument cannot be priced (live provider without coverage, or a provider outage) the
 * service returns an explicitly unavailable quote. It never falls back to a generated price while
 * live mode is active.</p>
 */
@Service
public class MarketDataService {

    private final MarketDataRegistry registry;
    private final InstrumentService instrumentService;
    private final CandleService candleService;

    /** Short-lived quote cache so one page load issues one evaluation per instrument. */
    private final Map<Long, CachedQuote> quoteCache = new ConcurrentHashMap<>();

    public MarketDataService(MarketDataRegistry registry,
                             InstrumentService instrumentService,
                             CandleService candleService) {
        this.registry = registry;
        this.instrumentService = instrumentService;
        this.candleService = candleService;
    }

    private record CachedQuote(QuoteSnapshot snapshot, long expiresAtMillis, String unavailableReason) {
    }

    // ---------------------------------------------------------------------------------------
    // Quotes
    // ---------------------------------------------------------------------------------------

    /** Cached quote for one instrument. */
    public QuoteSnapshot quoteSnapshot(Instrument instrument) {
        return quoteSnapshot(instrument, false);
    }

    /**
     * @param force when true the cache is bypassed. The trading engine uses this so a fill is always
     *              priced at the freshest available tick.
     */
    public QuoteSnapshot quoteSnapshot(Instrument instrument, boolean force) {
        long now = System.currentTimeMillis();
        long ttl = registry.isLiveMode() ? Math.max(1000L, registry.liveLatencyMillis() + 3000L)
                : Math.max(250L, quoteTtlMillis());
        CachedQuote cached = quoteCache.get(instrument.getId());
        if (!force && cached != null && cached.expiresAtMillis() > now) {
            return cached.snapshot();
        }
        MarketDataProvider provider = registry.providerFor(instrument);
        if (!provider.supports(instrument)) {
            String reason = provider.unsupportedReason(instrument);
            QuoteSnapshot unavailable = QuoteSnapshot.unavailable(instrument.getSymbol(), provider.source());
            quoteCache.put(instrument.getId(), new CachedQuote(unavailable, now + 2000, reason));
            return unavailable;
        }
        Optional<QuoteSnapshot> fetched = provider.quote(instrument);
        if (fetched.isEmpty()) {
            String reason = provider.live()
                    ? "Live provider returned no price for " + instrument.getSymbol()
                    + ". TradeX will not substitute a simulated price during a live session."
                    : "No price available for " + instrument.getSymbol() + ".";
            QuoteSnapshot unavailable = QuoteSnapshot.unavailable(instrument.getSymbol(), provider.source());
            quoteCache.put(instrument.getId(), new CachedQuote(unavailable, now + 2000, reason));
            return unavailable;
        }
        quoteCache.put(instrument.getId(), new CachedQuote(fetched.get(), now + ttl, null));
        return fetched.get();
    }

    /** Reason a quote is unavailable, or {@code null} when the price is usable. */
    public String unavailableReason(Instrument instrument) {
        CachedQuote cached = quoteCache.get(instrument.getId());
        if (cached != null && cached.unavailableReason() != null && cached.expiresAtMillis() > System.currentTimeMillis()) {
            return cached.unavailableReason();
        }
        return registry.unsupportedReason(instrument);
    }

    /** Batched quotes for a list of instruments, one provider round-trip where possible. */
    public Map<Long, QuoteSnapshot> quoteSnapshots(List<Instrument> instruments, boolean force) {
        Map<Long, QuoteSnapshot> result = new HashMap<>(instruments.size() * 2);
        List<Instrument> misses = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Instrument instrument : instruments) {
            CachedQuote cached = quoteCache.get(instrument.getId());
            if (!force && cached != null && cached.expiresAtMillis() > now) {
                result.put(instrument.getId(), cached.snapshot());
            } else {
                misses.add(instrument);
            }
        }
        if (misses.isEmpty()) {
            return result;
        }
        // Group by provider so each upstream API is called once with the full symbol list.
        Map<String, List<Instrument>> grouped = new LinkedHashMap<>();
        for (Instrument instrument : misses) {
            String providerId = registry.providerFor(instrument).id();
            grouped.computeIfAbsent(providerId, k -> new ArrayList<>()).add(instrument);
        }
        for (List<Instrument> group : grouped.values()) {
            MarketDataProvider provider = registry.providerFor(group.get(0));
            List<Instrument> supported = new ArrayList<>();
            for (Instrument instrument : group) {
                if (provider.supports(instrument)) {
                    supported.add(instrument);
                } else {
                    String reason = provider.unsupportedReason(instrument);
                    QuoteSnapshot unavailable = QuoteSnapshot.unavailable(instrument.getSymbol(), provider.source());
                    result.put(instrument.getId(), unavailable);
                    quoteCache.put(instrument.getId(), new CachedQuote(unavailable, now + 3000, reason));
                }
            }
            if (supported.isEmpty()) {
                continue;
            }
            Map<Long, QuoteSnapshot> fetched = provider.quotes(supported);
            for (Instrument instrument : supported) {
                QuoteSnapshot snapshot = fetched.get(instrument.getId());
                if (snapshot == null) {
                    String reason = provider.live()
                            ? "Live provider returned no price for " + instrument.getSymbol()
                            : "No price available for " + instrument.getSymbol();
                    snapshot = QuoteSnapshot.unavailable(instrument.getSymbol(), provider.source());
                    result.put(instrument.getId(), snapshot);
                    quoteCache.put(instrument.getId(), new CachedQuote(snapshot, now + 2000, reason));
                } else {
                    result.put(instrument.getId(), snapshot);
                    quoteCache.put(instrument.getId(), new CachedQuote(snapshot, now + 1000, null));
                }
            }
        }
        return result;
    }

    /** The price the trading engine will fill against. Throws when the instrument cannot be priced. */
    public BigDecimal executionPrice(Instrument instrument, BigDecimal fallback) {
        QuoteSnapshot snapshot = quoteSnapshot(instrument, true);
        if (snapshot.lastPrice() != null && snapshot.lastPrice().signum() > 0) {
            return Decimals.toTick(snapshot.lastPrice(), instrument.getTickSize());
        }
        if (fallback != null && fallback.signum() > 0) {
            return Decimals.toTick(fallback, instrument.getTickSize());
        }
        throw new MarketDataUnavailableException(
                "Cannot determine an execution price for " + instrument.getSymbol()
                        + ": " + Optional.ofNullable(unavailableReason(instrument)).orElse("no quote available"));
    }

    // ---------------------------------------------------------------------------------------
    // DTO mapping
    // ---------------------------------------------------------------------------------------

    public QuoteDto toDto(Instrument instrument) {
        QuoteSnapshot snapshot = quoteSnapshot(instrument);
        String reason = unavailableReason(instrument);
        boolean available = snapshot.lastPrice() != null;
        MarketDataProvider provider = registry.providerFor(instrument);
        return new QuoteDto(
                instrument.getSymbol(),
                instrument.getName(),
                instrument.getExchange(),
                instrument.getAssetClass(),
                instrument.getCurrency(),
                snapshot.lastPrice(),
                snapshot.previousClose(),
                available ? Decimals.money(snapshot.change()) : null,
                available ? snapshot.changePercent().setScale(4, java.math.RoundingMode.HALF_UP) : null,
                snapshot.dayOpen(),
                snapshot.dayHigh(),
                snapshot.dayLow(),
                snapshot.dayVolume(),
                snapshot.bid(),
                snapshot.ask(),
                instrument.isTradable() && available,
                snapshot.source(),
                label(snapshot.source()),
                provider.id(),
                available,
                available ? null : reason,
                snapshot.asOf());
    }

    public QuoteDto toDto(Instrument instrument, QuoteSnapshot snapshot, String reason) {
        boolean available = snapshot != null && snapshot.lastPrice() != null;
        if (snapshot == null) {
            snapshot = QuoteSnapshot.unavailable(instrument.getSymbol(), registry.activeSource());
        }
        return new QuoteDto(
                instrument.getSymbol(),
                instrument.getName(),
                instrument.getExchange(),
                instrument.getAssetClass(),
                instrument.getCurrency(),
                snapshot.lastPrice(),
                snapshot.previousClose(),
                available ? Decimals.money(snapshot.change()) : null,
                available ? snapshot.changePercent().setScale(4, java.math.RoundingMode.HALF_UP) : null,
                snapshot.dayOpen(),
                snapshot.dayHigh(),
                snapshot.dayLow(),
                snapshot.dayVolume(),
                snapshot.bid(),
                snapshot.ask(),
                instrument.isTradable() && available,
                snapshot.source(),
                label(snapshot.source()),
                registry.providerFor(instrument).id(),
                available,
                available ? null : reason,
                snapshot.asOf());
    }

    public static String label(MarketDataSource source) {
        return source == MarketDataSource.LIVE
                ? "LIVE"
                : "SIMULATED";
    }

    // ---------------------------------------------------------------------------------------
    // Candles
    // ---------------------------------------------------------------------------------------

    public CandleSeriesDto candleSeries(Instrument instrument, Timeframe timeframe, int limit) {
        CandleService.Series series = candleService.series(instrument, timeframe, limit);
        List<CandleSeriesDto.CandleDto> candles = series.bars().stream()
                .map(bar -> new CandleSeriesDto.CandleDto(
                        bar.openTime().getEpochSecond(),
                        bar.openTime(),
                        bar.open(),
                        bar.high(),
                        bar.low(),
                        bar.close(),
                        bar.volume()))
                .toList();
        return new CandleSeriesDto(
                instrument.getSymbol(),
                instrument.getName(),
                instrument.getCurrency(),
                timeframe,
                timeframe.code(),
                candles,
                series.source(),
                label(series.source()),
                series.live(),
                series.note(),
                Instant.now());
    }

    // ---------------------------------------------------------------------------------------
    // Indicators
    // ---------------------------------------------------------------------------------------

    /**
     * Computes the requested indicators over the exact candle series the chart is displaying, so an
     * overlay can never drift away from the candles it is drawn on.
     *
     * @param specifications entries such as {@code sma:20}, {@code ema:50}, {@code rsi:14},
     *                       {@code macd:12-26-9}, {@code bb:20-2}
     */
    public IndicatorSetDto indicators(Instrument instrument, Timeframe timeframe, int limit,
                                      List<String> specifications) {
        CandleSeriesDto series = candleSeries(instrument, timeframe, limit);
        List<CandleSeriesDto.CandleDto> candles = series.candles();
        List<BigDecimal> closes = candles.stream().map(CandleSeriesDto.CandleDto::close).toList();
        List<Long> times = candles.stream().map(CandleSeriesDto.CandleDto::time).toList();

        List<Specification> specs = specifications == null || specifications.isEmpty()
                ? List.of(parseSpecification("sma:20"), parseSpecification("ema:50"))
                : specifications.stream().map(MarketDataService::parseSpecification).toList();

        Map<String, IndicatorSetDto.Series> computed = new LinkedHashMap<>();
        for (Specification spec : specs) {
            String key = spec.key();
            switch (spec.name()) {
                case "sma" -> {
                    int period = spec.arg(0, 20);
                    computed.put(key, new IndicatorSetDto.Series(key, "SMA " + period, "price", "line",
                            Map.of("period", period),
                            IndicatorCalculator.sma(closes, period), null, null, null, null, null));
                }
                case "ema" -> {
                    int period = spec.arg(0, 50);
                    computed.put(key, new IndicatorSetDto.Series(key, "EMA " + period, "price", "line",
                            Map.of("period", period),
                            IndicatorCalculator.ema(closes, period), null, null, null, null, null));
                }
                case "rsi" -> {
                    int period = spec.arg(0, 14);
                    computed.put(key, new IndicatorSetDto.Series(key, "RSI " + period, "rsi", "line",
                            Map.of("period", period, "overbought", 70, "oversold", 30),
                            IndicatorCalculator.rsi(closes, period), null, null, null, null, null));
                }
                case "macd" -> {
                    int fast = spec.arg(0, 12);
                    int slow = spec.arg(1, 26);
                    int signalLength = spec.arg(2, 9);
                    IndicatorCalculator.MacdResult result = IndicatorCalculator.macd(closes, fast, slow, signalLength);
                    computed.put(key, new IndicatorSetDto.Series(key,
                            "MACD " + fast + "-" + slow + "-" + signalLength, "macd", "macd",
                            Map.of("fast", fast, "slow", slow, "signal", signalLength),
                            result.macdLine(), null, null, null, result.signalLine(), result.histogram()));
                }
                case "bb" -> {
                    int period = spec.arg(0, 20);
                    BigDecimal multiplier = BigDecimal.valueOf(spec.arg(1, 2));
                    IndicatorCalculator.BollingerResult result =
                            IndicatorCalculator.bollinger(closes, period, multiplier);
                    computed.put(key, new IndicatorSetDto.Series(key,
                            "BB " + period + " x" + multiplier.stripTrailingZeros().toPlainString(),
                            "price", "band", Map.of("period", period, "multiplier", multiplier),
                            result.upper(), result.upper(), result.middle(), result.lower(), null, null));
                }
                default -> throw new IllegalArgumentException(
                        "Unsupported indicator '" + spec.name() + "'. Supported: sma, ema, rsi, macd, bb");
            }
        }

        return new IndicatorSetDto(
                instrument.getSymbol(),
                instrument.getName(),
                timeframe.code(),
                candles.size(),
                times,
                computed,
                series.dataSource(),
                label(series.dataSource()),
                Instant.now());
    }

    /** Indicator values for every requested specification, keyed by specification. */
    public Map<String, List<BigDecimal>> indicatorValues(Instrument instrument, Timeframe timeframe, int limit,
                                                         List<String> specifications) {
        CandleSeriesDto series = candleSeries(instrument, timeframe, limit);
        List<BigDecimal> closes = series.candles().stream().map(CandleSeriesDto.CandleDto::close).toList();
        Map<String, List<BigDecimal>> result = new LinkedHashMap<>();
        for (String raw : specifications) {
            Specification spec = parseSpecification(raw);
            result.put(spec.key(), switch (spec.name()) {
                case "sma" -> IndicatorCalculator.sma(closes, spec.arg(0, 20));
                case "ema" -> IndicatorCalculator.ema(closes, spec.arg(0, 50));
                case "rsi" -> IndicatorCalculator.rsi(closes, spec.arg(0, 14));
                case "macd" -> IndicatorCalculator.macd(closes, spec.arg(0, 12), spec.arg(1, 26), spec.arg(2, 9)).macdLine();
                case "bb" -> IndicatorCalculator.bollinger(closes, spec.arg(0, 20),
                        BigDecimal.valueOf(spec.arg(1, 2))).middle();
                default -> List.<BigDecimal>of();
            });
        }
        return result;
    }

    private record Specification(String name, List<Integer> args) {
        /**
         * Canonical form of the specification with the exact separators callers use in the
         * {@code indicators} request parameter ({@code sma:20}, {@code macd:12-26-9},
         * {@code bb:20-2}). Clients can look responses up by the very string they sent.
         */
        String key() {
            StringBuilder builder = new StringBuilder(name);
            for (int i = 0; i < args.size(); i++) {
                builder.append(i == 0 ? ':' : '-').append(args.get(i));
            }
            return builder.toString();
        }

        int arg(int index, int fallback) {
            if (args == null || index >= args.size() || args.get(index) == null) {
                return fallback;
            }
            return args.get(index);
        }
    }

    /** Parses {@code sma:20}, {@code macd:12-26-9}, {@code bb:20-2}. */
    static Specification parseSpecification(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Indicator specification must not be blank");
        }
        String trimmed = raw.trim().toLowerCase();
        String name = trimmed;
        List<Integer> args = new ArrayList<>();
        int colon = trimmed.indexOf(':');
        if (colon > 0) {
            name = trimmed.substring(0, colon);
            String argPart = trimmed.substring(colon + 1);
            if (!argPart.isBlank()) {
                for (String piece : argPart.split("[-/:]")) {
                    if (piece.isBlank()) {
                        continue;
                    }
                    try {
                        args.add(Integer.parseInt(piece.trim()));
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException("Invalid indicator argument in '" + raw + "'");
                    }
                }
            }
        }
        switch (name) {
            case "sma", "ema", "rsi", "macd", "bb" -> {
                return new Specification(name, args);
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported indicator '" + name + "'. Supported: sma, ema, rsi, macd, bb");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Market status
    // ---------------------------------------------------------------------------------------

    public MarketStatusDto marketStatus() {
        boolean live = registry.isLiveMode();
        MarketStatus status = live ? MarketStatus.OPEN : MarketStatus.SIMULATED_ALWAYS_OPEN;
        Map<String, String> sessions = new LinkedHashMap<>();
        sessions.put("mode", live ? "LIVE" : "SIMULATED");
        sessions.put("venues", live ? registry.live().displayName() : "NSE, BSE, CRYPTO (simulated)");
        sessions.put("note", live
                ? "Prices are streamed from " + registry.activeProviderId()
                + ". Paper trading only: orders never reach a real exchange."
                : "Simulated venue is always open; every price is generated by TradeX.");
        return new MarketStatusDto(
                status,
                live ? "LIVE MARKET" : "SIMULATED MARKET (ALWAYS OPEN)",
                registry.activeSource(),
                registry.activeLabel(),
                registry.activeProviderId(),
                !live,
                Instant.now(),
                sessions);
    }

    /** All active instruments keyed by symbol, for callers that need the master list. */
    public Map<String, Instrument> instrumentsBySymbol() {
        Map<String, Instrument> map = new LinkedHashMap<>();
        for (Instrument instrument : instrumentService.allActive()) {
            map.put(instrument.getSymbol(), instrument);
        }
        return Collections.unmodifiableMap(map);
    }

    /** Clears the quote cache; used after a mode change or in tests. */
    public void invalidateQuotes() {
        quoteCache.clear();
    }

    private long quoteTtlMillis() {
        return registry.simulatedTickMillis();
    }
}

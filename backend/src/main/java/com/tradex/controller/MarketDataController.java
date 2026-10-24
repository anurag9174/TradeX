package com.tradex.controller;

import com.tradex.dto.market.CandleSeriesDto;
import com.tradex.dto.market.IndicatorSetDto;
import com.tradex.dto.market.InstrumentDto;
import com.tradex.dto.market.MarketStatusDto;
import com.tradex.dto.market.QuoteDto;
import com.tradex.enums.Timeframe;
import com.tradex.exception.BadRequestException;
import com.tradex.marketdata.MarketDataRegistry;
import com.tradex.marketdata.MarketDataService;
import com.tradex.service.InstrumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Public market-data reads.
 *
 * <p>None of these carry user data, so they are open. Every payload states which data source produced
 * it, so a client can render the SIMULATED badge without asking a second question.</p>
 */
@RestController
@RequestMapping("/api/market")
@Tag(name = "Market Data", description = "Instruments, quotes, candles, indicators and market status")
public class MarketDataController {

    private final MarketDataService marketDataService;
    private final InstrumentService instrumentService;
    private final MarketDataRegistry registry;

    public MarketDataController(MarketDataService marketDataService,
                                InstrumentService instrumentService,
                                MarketDataRegistry registry) {
        this.marketDataService = marketDataService;
        this.instrumentService = instrumentService;
        this.registry = registry;
    }

    @GetMapping("/instruments")
    @Operation(summary = "Every instrument the platform knows about, with tradeability and provider support")
    public ResponseEntity<List<InstrumentDto>> instruments(
            @RequestParam(name = "assetClass", required = false) String assetClass,
            @RequestParam(name = "tradableOnly", required = false, defaultValue = "false") boolean tradableOnly,
            @RequestParam(name = "search", required = false) String search) {
        List<InstrumentDto> all = instrumentService.allActive().stream()
                .filter(instrument -> assetClass == null || assetClass.isBlank()
                        || instrument.getAssetClass().name().equalsIgnoreCase(assetClass.trim()))
                .filter(instrument -> !tradableOnly || instrument.isTradable())
                .filter(instrument -> search == null || search.isBlank()
                        || instrument.getSymbol().toLowerCase().contains(search.trim().toLowerCase())
                        || instrument.getName().toLowerCase().contains(search.trim().toLowerCase()))
                .map(instrumentService::toDto)
                .toList();
        return ResponseEntity.ok(all);
    }

    @GetMapping("/instruments/{symbol}")
    @Operation(summary = "One instrument")
    public ResponseEntity<InstrumentDto> instrument(@PathVariable String symbol) {
        return ResponseEntity.ok(instrumentService.toDto(instrumentService.require(symbol)));
    }

    @GetMapping("/quote/{symbol}")
    @Operation(summary = "Latest quote for one instrument")
    public ResponseEntity<QuoteDto> quote(@PathVariable String symbol) {
        return ResponseEntity.ok(marketDataService.toDto(instrumentService.require(symbol)));
    }

    @GetMapping("/quotes")
    @Operation(summary = "Latest quotes for several instruments in one round-trip")
    public ResponseEntity<List<QuoteDto>> quotes(@RequestParam(name = "symbols") String symbols) {
        List<QuoteDto> quotes = Arrays.stream(symbols.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(symbol -> marketDataService.toDto(instrumentService.require(symbol)))
                .toList();
        return ResponseEntity.ok(quotes);
    }

    @GetMapping("/candles/{symbol}")
    @Operation(summary = "OHLCV candle series. Simulated data is labelled as such")
    public ResponseEntity<CandleSeriesDto> candles(
            @PathVariable String symbol,
            @RequestParam(name = "timeframe", required = false, defaultValue = "15m") String timeframe,
            @RequestParam(name = "limit", required = false, defaultValue = "500") int limit) {
        return ResponseEntity.ok(marketDataService.candleSeries(
                instrumentService.require(symbol), parseTimeframe(timeframe), limit));
    }

    @GetMapping("/indicators/{symbol}")
    @Operation(summary = "SMA / EMA / RSI / MACD / Bollinger, computed from the same candles the chart shows")
    public ResponseEntity<IndicatorSetDto> indicators(
            @PathVariable String symbol,
            @RequestParam(name = "timeframe", required = false, defaultValue = "15m") String timeframe,
            @RequestParam(name = "limit", required = false, defaultValue = "500") int limit,
            @RequestParam(name = "indicators", required = false) List<String> indicators) {
        return ResponseEntity.ok(marketDataService.indicators(
                instrumentService.require(symbol), parseTimeframe(timeframe), limit, indicators));
    }

    @GetMapping("/timeframes")
    @Operation(summary = "Supported chart timeframes")
    public ResponseEntity<List<Map<String, String>>> timeframes() {
        return ResponseEntity.ok(Arrays.stream(Timeframe.values())
                .map(tf -> Map.of("code", tf.code(), "name", tf.name(), "seconds", String.valueOf(tf.seconds())))
                .toList());
    }

    @GetMapping("/status")
    @Operation(summary = "Market session status and the active data source")
    public ResponseEntity<MarketStatusDto> status() {
        return ResponseEntity.ok(marketDataService.marketStatus());
    }

    @GetMapping("/providers")
    @Operation(summary = "Market data provider diagnostics, including which instruments a live provider covers")
    public ResponseEntity<Map<String, Object>> providers() {
        Map<String, Object> diagnostics = new java.util.LinkedHashMap<>(registry.diagnostics());
        diagnostics.put("liveKeyConfigured", registry.liveConfigured());
        diagnostics.put("instrumentCoverage", instrumentService.allActive().stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.tradex.entity.Instrument::getSymbol,
                        instrument -> registry.isSupported(instrument)
                                ? registry.providerFor(instrument).id()
                                : "UNSUPPORTED: " + registry.unsupportedReason(instrument),
                        (a, b) -> a,
                        java.util.LinkedHashMap::new)));
        return ResponseEntity.ok(diagnostics);
    }

    private Timeframe parseTimeframe(String raw) {
        try {
            return Timeframe.fromCode(raw);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("INVALID_TIMEFRAME", ex.getMessage()
                    + " Supported: 1m, 5m, 15m, 30m, 1h, 4h, 1d, 1w.");
        }
    }
}

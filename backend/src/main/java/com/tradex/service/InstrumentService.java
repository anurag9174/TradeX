package com.tradex.service;

import com.tradex.dto.market.InstrumentDto;
import com.tradex.entity.Instrument;
import com.tradex.enums.AssetClass;
import com.tradex.exception.ResourceNotFoundException;
import com.tradex.marketdata.MarketDataProvider;
import com.tradex.marketdata.MarketDataRegistry;
import com.tradex.repository.InstrumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read model over the instrument master. Instruments change rarely, so they are cached in memory
 * and refreshed on demand.
 */
@Service
public class InstrumentService {

    private final InstrumentRepository instrumentRepository;
    private final MarketDataRegistry registry;

    private final ConcurrentHashMap<Long, Instrument> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instrument> bySymbol = new ConcurrentHashMap<>();
    private volatile List<Instrument> activeCache = List.of();

    public InstrumentService(InstrumentRepository instrumentRepository, MarketDataRegistry registry) {
        this.instrumentRepository = instrumentRepository;
        this.registry = registry;
    }

    @Transactional(readOnly = true)
    public synchronized void refresh() {
        List<Instrument> all = instrumentRepository.findAll();
        Map<Long, Instrument> idMap = new ConcurrentHashMap<>();
        Map<String, Instrument> symbolMap = new ConcurrentHashMap<>();
        for (Instrument instrument : all) {
            idMap.put(instrument.getId(), instrument);
            symbolMap.put(instrument.getSymbol().toUpperCase(), instrument);
        }
        byId.clear();
        byId.putAll(idMap);
        bySymbol.clear();
        bySymbol.putAll(symbolMap);
        activeCache = all.stream()
                .filter(Instrument::isActive)
                .sorted(Comparator.comparingInt(Instrument::getSortOrder).thenComparing(Instrument::getSymbol))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Instrument> allActive() {
        if (activeCache.isEmpty()) {
            refresh();
        }
        return activeCache;
    }

    public List<Instrument> tradableActive() {
        return allActive().stream().filter(Instrument::isTradable).toList();
    }

    public List<Instrument> byAssetClass(AssetClass assetClass) {
        return allActive().stream().filter(i -> i.getAssetClass() == assetClass).toList();
    }

    public Optional<Instrument> find(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Optional.empty();
        }
        Instrument cached = bySymbol.get(symbol.trim().toUpperCase());
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<Instrument> loaded = instrumentRepository.findBySymbolIgnoreCase(symbol.trim());
        loaded.ifPresent(this::cache);
        return loaded;
    }

    /** Lookup by database identifier, served from the same cache. */
    public Optional<Instrument> find(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        Instrument cached = byId.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<Instrument> loaded = instrumentRepository.findById(id);
        loaded.ifPresent(this::cache);
        return loaded;
    }

    public Instrument require(String symbol) {
        return find(symbol).orElseThrow(() ->
                new ResourceNotFoundException("INSTRUMENT_NOT_FOUND", "Unknown instrument: " + symbol));
    }

    public Instrument require(Long id) {
        Instrument cached = byId.get(id);
        if (cached != null) {
            return cached;
        }
        Instrument loaded = instrumentRepository.findById(id).orElseThrow(() ->
                new ResourceNotFoundException("INSTRUMENT_NOT_FOUND", "Unknown instrument id: " + id));
        cache(loaded);
        return loaded;
    }

    public Map<Long, Instrument> requireAll(List<Long> ids) {
        Map<Long, Instrument> result = new ConcurrentHashMap<>();
        for (Long id : ids) {
            result.put(id, require(id));
        }
        return result;
    }

    public InstrumentDto toDto(Instrument instrument) {
        MarketDataProvider provider = registry.providerFor(instrument);
        boolean simulated = provider.source() == com.tradex.enums.MarketDataSource.SIMULATED;
        boolean supported = registry.isSupported(instrument);
        String note = supported
                ? provider.displayName()
                : registry.unsupportedReason(instrument);
        return new InstrumentDto(
                instrument.getId(),
                instrument.getSymbol(),
                instrument.getName(),
                instrument.getExchange(),
                instrument.getAssetClass(),
                instrument.getCurrency(),
                instrument.getTickSize(),
                instrument.getLotSize(),
                instrument.isTradable(),
                instrument.isActive(),
                supported,
                instrument.getSector(),
                simulated,
                provider.source(),
                note);
    }

    private void cache(Instrument instrument) {
        byId.put(instrument.getId(), instrument);
        bySymbol.put(instrument.getSymbol().toUpperCase(), instrument);
    }
}

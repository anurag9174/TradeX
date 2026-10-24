package com.tradex.service;

import com.tradex.dto.market.InstrumentDto;
import com.tradex.dto.market.QuoteDto;
import com.tradex.dto.market.WatchlistDto;
import com.tradex.entity.Instrument;
import com.tradex.entity.TradingAccount;
import com.tradex.entity.WatchlistItem;
import com.tradex.exception.BadRequestException;
import com.tradex.exception.ConflictException;
import com.tradex.exception.ResourceNotFoundException;
import com.tradex.marketdata.MarketDataRegistry;
import com.tradex.marketdata.MarketDataService;
import com.tradex.marketdata.QuoteSnapshot;
import com.tradex.repository.TradingAccountRepository;
import com.tradex.repository.WatchlistItemRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-account instrument watchlist.
 *
 * <p>Watchlists are owned by an account, so a user only ever sees their own. New accounts are seeded
 * from the {@code watchlist_template} table that the V2 migration populates, which keeps the default
 * symbol list in one place and out of the code.</p>
 */
@Service
public class WatchlistService {

    private static final int MAX_ITEMS = 60;

    private final WatchlistItemRepository watchlistRepository;
    private final TradingAccountRepository accountRepository;
    private final InstrumentService instrumentService;
    private final MarketDataService marketDataService;
    private final MarketDataRegistry registry;
    private final JdbcTemplate jdbcTemplate;

    public WatchlistService(WatchlistItemRepository watchlistRepository,
                            TradingAccountRepository accountRepository,
                            InstrumentService instrumentService,
                            MarketDataService marketDataService,
                            MarketDataRegistry registry,
                            JdbcTemplate jdbcTemplate) {
        this.watchlistRepository = watchlistRepository;
        this.accountRepository = accountRepository;
        this.instrumentService = instrumentService;
        this.marketDataService = marketDataService;
        this.registry = registry;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Copies the default template into a freshly created account. */
    @Transactional
    public void seedDefaults(TradingAccount account) {
        if (watchlistRepository.countByAccountId(account.getId()) > 0) {
            return;
        }
        List<String> symbols;
        try {
            symbols = jdbcTemplate.queryForList(
                    "SELECT symbol FROM watchlist_template ORDER BY sort_order ASC", String.class);
        } catch (RuntimeException ex) {
            symbols = List.of("NIFTY50", "SENSEX", "RELIANCE", "TCS", "INFY", "HDFCBANK", "BTCINR", "ETHINR",
                    "BTCUSD", "ETHUSD");
        }
        int order = 10;
        for (String symbol : symbols) {
            Instrument instrument = instrumentService.find(symbol).orElse(null);
            if (instrument == null) {
                continue;
            }
            watchlistRepository.save(new WatchlistItem(account.getId(), instrument.getId(), order));
            order += 10;
        }
    }

    @Transactional(readOnly = true)
    public WatchlistDto list(Long userId) {
        TradingAccount account = requireAccount(userId);
        List<WatchlistItem> items = watchlistRepository.findByAccountIdOrderBySortOrderAscIdAsc(account.getId());
        if (items.isEmpty()) {
            return new WatchlistDto(List.of(), registry.activeSource(), registry.activeLabel(), Instant.now());
        }
        Map<Long, Instrument> instruments = new HashMap<>(items.size() * 2);
        List<Instrument> ordered = new ArrayList<>(items.size());
        for (WatchlistItem item : items) {
            instrumentService.find(item.getInstrumentId()).ifPresent(instrument -> {
                instruments.put(item.getInstrumentId(), instrument);
                ordered.add(instrument);
            });
        }
        Map<Long, QuoteSnapshot> quotes = marketDataService.quoteSnapshots(ordered, false);

        List<WatchlistDto.Item> dtoItems = new ArrayList<>(items.size());
        for (WatchlistItem item : items) {
            Instrument instrument = instruments.get(item.getInstrumentId());
            if (instrument == null) {
                continue;
            }
            QuoteSnapshot snapshot = quotes.get(instrument.getId());
            String reason = snapshot == null || snapshot.lastPrice() == null
                    ? marketDataService.unavailableReason(instrument) : null;
            QuoteDto quote = marketDataService.toDto(instrument, snapshot, reason);
            dtoItems.add(new WatchlistDto.Item(
                    instrument.getId(),
                    instrument.getSymbol(),
                    instrument.getName(),
                    instrument.getExchange(),
                    instrument.getAssetClass().name(),
                    item.getSortOrder(),
                    quote));
        }
        return new WatchlistDto(dtoItems, registry.activeSource(), registry.activeLabel(), Instant.now());
    }

    @Transactional
    public WatchlistDto.Item add(Long userId, String symbol) {
        TradingAccount account = requireAccount(userId);
        Instrument instrument = instrumentService.require(symbol);
        if (watchlistRepository.existsByAccountIdAndInstrumentId(account.getId(), instrument.getId())) {
            throw new ConflictException("ALREADY_WATCHED",
                    instrument.getSymbol() + " is already on your watchlist.");
        }
        long count = watchlistRepository.countByAccountId(account.getId());
        if (count >= MAX_ITEMS) {
            throw new BadRequestException("WATCHLIST_FULL",
                    "A watchlist can hold at most " + MAX_ITEMS + " instruments.");
        }
        int nextOrder = watchlistRepository.findByAccountIdOrderBySortOrderAscIdAsc(account.getId()).stream()
                .mapToInt(WatchlistItem::getSortOrder).max().orElse(0) + 10;
        WatchlistItem saved = watchlistRepository.save(new WatchlistItem(account.getId(), instrument.getId(), nextOrder));
        QuoteSnapshot snapshot = marketDataService.quoteSnapshot(instrument);
        String reason = snapshot.lastPrice() == null ? marketDataService.unavailableReason(instrument) : null;
        return new WatchlistDto.Item(instrument.getId(), instrument.getSymbol(), instrument.getName(),
                instrument.getExchange(), instrument.getAssetClass().name(), saved.getSortOrder(),
                marketDataService.toDto(instrument, snapshot, reason));
    }

    @Transactional
    public void remove(Long userId, String symbol) {
        TradingAccount account = requireAccount(userId);
        Instrument instrument = instrumentService.require(symbol);
        long removed = watchlistRepository.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
                .map(item -> {
                    watchlistRepository.delete(item);
                    return 1L;
                })
                .orElseThrow(() -> new ResourceNotFoundException("WATCHLIST_ITEM_NOT_FOUND",
                        instrument.getSymbol() + " is not on your watchlist."));
        if (removed == 0) {
            throw new ResourceNotFoundException("WATCHLIST_ITEM_NOT_FOUND",
                    instrument.getSymbol() + " is not on your watchlist.");
        }
    }

    /** Everything the user could add, annotated with tradeability and data-source support. */
    @Transactional(readOnly = true)
    public List<InstrumentDto> catalogue() {
        return instrumentService.allActive().stream().map(instrumentService::toDto).toList();
    }

    private TradingAccount requireAccount(Long userId) {
        return accountRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("ACCOUNT_NOT_FOUND",
                        "No trading account exists for this user."));
    }
}

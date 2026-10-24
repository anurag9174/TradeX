package com.tradex.websocket;

import com.tradex.dto.account.AccountDto;
import com.tradex.dto.market.QuoteTickDto;
import com.tradex.entity.Instrument;
import com.tradex.enums.MarketDataSource;
import com.tradex.marketdata.MarketDataRegistry;
import com.tradex.marketdata.MarketDataService;
import com.tradex.marketdata.QuoteSnapshot;
import com.tradex.service.InstrumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pushes live market ticks to subscribed browsers.
 *
 * <p>Cadence follows the data source: the simulated feed ticks every
 * {@code tradex.marketdata.simulated-tick-millis}, while a live provider is polled every
 * {@code tradex.marketdata.quote-poll-seconds} so we stay inside its rate limits. Crucially, live
 * mode never fabricates a tick: when the provider has nothing, the tick is sent with
 * {@code available = false} and the UI greys the row out.</p>
 */
@Component
public class QuoteStreamBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(QuoteStreamBroadcaster.class);

    /** Keeps each STOMP frame comfortably small. */
    private static final int BATCH_SIZE = 40;

    private final SimpMessagingTemplate messagingTemplate;
    private final InstrumentService instrumentService;
    private final MarketDataService marketDataService;
    private final MarketDataRegistry registry;

    private volatile boolean paused = false;

    public QuoteStreamBroadcaster(SimpMessagingTemplate messagingTemplate,
                                  InstrumentService instrumentService,
                                  MarketDataService marketDataService,
                                  MarketDataRegistry registry) {
        this.messagingTemplate = messagingTemplate;
        this.instrumentService = instrumentService;
        this.marketDataService = marketDataService;
        this.registry = registry;
    }

    /** Simulated mode cadence. */
    @Scheduled(fixedDelayString = "${tradex.marketdata.simulated-tick-millis:1000}")
    public void simulatedTick() {
        if (registry.isLiveMode()) {
            return;
        }
        broadcast();
    }

    /** Live mode cadence. */
    @Scheduled(fixedDelayString = "${tradex.marketdata.quote-poll-millis:15000}")
    public void liveTick() {
        if (!registry.isLiveMode()) {
            return;
        }
        broadcast();
    }

    private void broadcast() {
        if (paused) {
            return;
        }
        List<Instrument> instruments = instrumentService.allActive();
        if (instruments.isEmpty()) {
            return;
        }
        try {
            Map<Long, QuoteSnapshot> quotes = marketDataService.quoteSnapshots(instruments, false);
            List<QuoteTickDto> ticks = new ArrayList<>(instruments.size());
            for (Instrument instrument : instruments) {
                QuoteSnapshot snapshot = quotes.get(instrument.getId());
                ticks.add(toTick(instrument, snapshot));
            }
            for (int i = 0; i < ticks.size(); i += BATCH_SIZE) {
                List<QuoteTickDto> batch = ticks.subList(i, Math.min(i + BATCH_SIZE, ticks.size()));
                messagingTemplate.convertAndSend("/topic/quotes", batch);
            }
        } catch (RuntimeException ex) {
            log.warn("Quote broadcast failed: {}", ex.getMessage());
        }
    }

    private QuoteTickDto toTick(Instrument instrument, QuoteSnapshot snapshot) {
        boolean available = snapshot != null && snapshot.lastPrice() != null;
        if (!available) {
            return new QuoteTickDto(instrument.getSymbol(), instrument.getCurrency(), null, null, null, null, null, null,
                    BigDecimal.ZERO, false, registry.activeSource(),
                    MarketDataService.label(registry.activeSource()),
                    java.time.Instant.now());
        }
        return new QuoteTickDto(
                instrument.getSymbol(),
                instrument.getCurrency(),
                snapshot.lastPrice(),
                snapshot.previousClose(),
                round(snapshot.change()),
                snapshot.changePercent().setScale(4, RoundingMode.HALF_UP),
                snapshot.dayHigh(),
                snapshot.dayLow(),
                snapshot.dayVolume(),
                true,
                snapshot.source() == null ? MarketDataSource.SIMULATED : snapshot.source(),
                MarketDataService.label(snapshot.source() == null ? MarketDataSource.SIMULATED : snapshot.source()),
                snapshot.asOf());
    }

    private static BigDecimal round(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    /** Pushes an authoritative account snapshot to its owner. */
    public void publishAccount(Long accountId, AccountDto account) {
        try {
            messagingTemplate.convertAndSend("/topic/account/" + accountId, account);
        } catch (RuntimeException ex) {
            log.debug("Account broadcast failed for {}: {}", accountId, ex.getMessage());
        }
    }

    /** Used by tests and maintenance to silence the stream. */
    public void setPaused(boolean value) {
        this.paused = value;
    }
}

package com.tradex.marketdata;

import com.tradex.config.MarketDataProperties;
import com.tradex.entity.Instrument;
import com.tradex.enums.MarketDataSource;
import com.tradex.enums.Timeframe;
import com.tradex.repository.MarketCandleRepository;
import com.tradex.service.InstrumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Generates and persists the initial simulated candle history exactly once.
 *
 * <p>Without this, the first chart request on a cold database would have to synthesise thousands of
 * bars inline. Bootstrapping moves that cost to start-up, runs it in parallel across instruments and
 * writes with JDBC batching, so a fresh install is usable in a couple of seconds.</p>
 *
 * <p>The step is idempotent: per instrument/timeframe the newest persisted bar decides whether the
 * key already has history. If it does, nothing is written.</p>
 */
@Component
@Order(10)
public class MarketDataBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MarketDataBootstrap.class);

    /** Bars to generate per timeframe when a key is empty. Deliberately modest to keep boot fast. */
    private static final Map<Timeframe, Integer> HISTORY = Map.of(
            Timeframe.M1, 720,
            Timeframe.M5, 576,
            Timeframe.M15, 480,
            Timeframe.M30, 480,
            Timeframe.H1, 720,
            Timeframe.H4, 720,
            Timeframe.D1, 500,
            Timeframe.W1, 260);

    private static final int BATCH_SIZE = 500;

    private final MarketCandleRepository candleRepository;
    private final MarketDataRegistry registry;
    private final CandleFactory candleFactory;
    private final InstrumentService catalog;
    private final MarketDataProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final boolean bootstrapEnabled;

    public MarketDataBootstrap(MarketCandleRepository candleRepository,
                               MarketDataRegistry registry,
                               CandleFactory candleFactory,
                               InstrumentService catalog,
                               MarketDataProperties properties,
                               JdbcTemplate jdbcTemplate,
                               @Value("${tradex.marketdata.bootstrap-enabled:true}") boolean bootstrapEnabled) {
        this.candleRepository = candleRepository;
        this.registry = registry;
        this.candleFactory = candleFactory;
        this.catalog = catalog;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.bootstrapEnabled = bootstrapEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        registry.initialise();
        if (!bootstrapEnabled) {
            log.info("Simulated candle bootstrap disabled.");
            return;
        }
        if (registry.isLiveMode()) {
            log.info("Live market data mode: skipping simulated candle bootstrap.");
            return;
        }
        List<Instrument> instruments = catalog.allActive();
        if (instruments.isEmpty()) {
            log.warn("No instruments found; nothing to bootstrap.");
            return;
        }
        long started = System.currentTimeMillis();
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(cores, instruments.size()));
        try {
            List<CompletableFuture<Integer>> futures = new ArrayList<>();
            for (Instrument instrument : instruments) {
                futures.add(CompletableFuture.supplyAsync(() -> bootstrapInstrument(instrument), executor));
            }
            int total = 0;
            for (CompletableFuture<Integer> future : futures) {
                total += future.join();
            }
            log.info("Simulated market bootstrap ready: {} candles in {} ms ({} instruments). "
                            + "Prices are generated, not real market data.",
                    total, System.currentTimeMillis() - started, instruments.size());
        } finally {
            executor.shutdown();
            try {
                executor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Generates and persists every timeframe for one instrument unless it is already present. */
    public int bootstrapInstrument(Instrument instrument) {
        Instant now = Instant.now();
        int written = 0;
        for (Map.Entry<Timeframe, Integer> entry : HISTORY.entrySet()) {
            Timeframe timeframe = entry.getKey();
            int limit = entry.getValue();
            if (hasFreshHistory(instrument, timeframe, limit, now)) {
                continue;
            }
            written += generate(instrument, timeframe, limit, now);
        }
        return written;
    }

    private boolean hasFreshHistory(Instrument instrument, Timeframe timeframe, int limit, Instant now) {
        // Simulated history only ever looks at simulated rows; live bars in the same key must not
        // make generated history look "fresh".
        Instant latest = candleRepository.findLatestOpenTimeForSource(
                instrument.getId(), timeframe, MarketDataSource.SIMULATED);
        if (latest == null) {
            return false;
        }
        Instant currentBucket = candleFactory.alignOpenTime(now, timeframe);
        // Fresh enough if the newest closed bar is the bucket immediately before the current one.
        Instant expected = currentBucket.minusSeconds(timeframe.seconds());
        return !latest.isBefore(expected);
    }

    private int generate(Instrument instrument, Timeframe timeframe, int limit, Instant now) {
        Instant currentBucket = candleFactory.alignOpenTime(now, timeframe);
        Instant start = currentBucket.minusSeconds((long) timeframe.seconds() * limit);
        List<CandleBar> bars = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            bars.add(candleFactory.closedBar(instrument, timeframe, start.plusSeconds((long) timeframe.seconds() * i)));
        }
        if (!properties.isPersistCandles()) {
            return bars.size();
        }
        String sql = "INSERT INTO market_candles "
                + "(instrument_id, timeframe, open_time, open_price, high_price, low_price, close_price, volume, source, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        ParameterizedPreparedStatementSetter<CandleBar> setter = (ps, bar) -> {
            ps.setLong(1, instrument.getId());
            ps.setString(2, timeframe.name());
            ps.setTimestamp(3, Timestamp.from(bar.openTime().truncatedTo(ChronoUnit.MICROS)));
            ps.setBigDecimal(4, bar.open());
            ps.setBigDecimal(5, bar.high());
            ps.setBigDecimal(6, bar.low());
            ps.setBigDecimal(7, bar.close());
            ps.setBigDecimal(8, bar.volume());
            ps.setString(9, MarketDataSource.SIMULATED.name());
            ps.setTimestamp(10, Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)));
        };
        try {
            jdbcTemplate.batchUpdate(sql, bars, BATCH_SIZE, setter);
        } catch (Exception ex) {
            // A concurrent instance may have written the same key; that is fine.
            log.debug("Skipped candle batch for {} {}: {}", instrument.getSymbol(), timeframe, ex.getMessage());
        }
        return bars.size();
    }
}

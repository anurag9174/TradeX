package com.tradex.marketdata;

import com.tradex.config.MarketDataProperties;
import com.tradex.entity.Instrument;
import com.tradex.enums.MarketDataSource;
import com.tradex.enums.Timeframe;
import com.tradex.repository.MarketCandleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists closed live candles so the chart can fall back to real bars when the provider is
 * unreachable.
 *
 * <h2>Why a separate bean</h2>
 * {@link CandleService#series} runs in a read-only transaction. Writes therefore happen here, in
 * their own transaction ({@link Propagation#REQUIRES_NEW}), and are strictly best-effort: a
 * storage failure must never become a chart failure.
 *
 * <h2>Collision safety</h2>
 * {@code market_candles} is keyed by (instrument, timeframe, open_time) - {@code source} is not part
 * of the key - so a simulated bar already occupying a bucket can never be replaced by a live one.
 * Inserts are gated on the newest open_time <em>across all sources</em>; anything at or before it is
 * skipped silently.
 */
@Service
public class LiveCandleStore {

    private static final Logger log = LoggerFactory.getLogger(LiveCandleStore.class);

    private static final int BATCH_SIZE = 500;

    private final MarketCandleRepository candleRepository;
    private final MarketDataProperties properties;
    private final JdbcTemplate jdbcTemplate;

    public LiveCandleStore(MarketCandleRepository candleRepository,
                           MarketDataProperties properties,
                           JdbcTemplate jdbcTemplate) {
        this.candleRepository = candleRepository;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Stores the given closed bars; returns how many rows were actually written. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int store(Instrument instrument, Timeframe timeframe, List<CandleBar> closedBars) {
        if (!properties.isPersistCandles() || closedBars == null || closedBars.isEmpty()) {
            return 0;
        }
        try {
            Instant gate = candleRepository.findLatestOpenTimeAnySource(instrument.getId(), timeframe);
            List<CandleBar> fresh = new ArrayList<>(closedBars.size());
            for (CandleBar bar : closedBars) {
                if (gate == null || bar.openTime().isAfter(gate)) {
                    fresh.add(bar);
                }
            }
            if (fresh.isEmpty()) {
                return 0;
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
                ps.setString(9, MarketDataSource.LIVE.name());
                ps.setTimestamp(10, Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)));
            };
            int[][] counts = jdbcTemplate.batchUpdate(sql, fresh, BATCH_SIZE, setter);
            int written = 0;
            for (int[] batch : counts) {
                written += batch.length;
            }
            return written;
        } catch (Exception ex) {
            // A concurrent writer may have taken the same keys; persistence is best-effort only.
            log.debug("Live candle persist skipped for {} {}: {}", instrument.getSymbol(), timeframe, ex.getMessage());
            return 0;
        }
    }
}

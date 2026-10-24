package com.tradex.entity;

import com.tradex.enums.MarketDataSource;
import com.tradex.enums.Timeframe;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single OHLCV candle. Rows are immutable once written: the loader only inserts closed
 * candles and never rewrites history, which keeps indicator math reproducible.
 */
@Entity
@Table(
        name = "market_candles",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_market_candles_instrument_interval_time",
                columnNames = {"instrument_id", "timeframe", "open_time"}),
        indexes = {
                @Index(name = "idx_market_candles_lookup", columnList = "instrument_id,timeframe,open_time"),
                @Index(name = "idx_market_candles_source", columnList = "source")
        })
public class MarketCandle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "timeframe", nullable = false, length = 8)
    private Timeframe timeframe;

    /** UTC instant of the candle open. */
    @Column(name = "open_time", nullable = false)
    private Instant openTime;

    @Column(name = "open_price", nullable = false, precision = 19, scale = 6)
    private BigDecimal open;

    @Column(name = "high_price", nullable = false, precision = 19, scale = 6)
    private BigDecimal high;

    @Column(name = "low_price", nullable = false, precision = 19, scale = 6)
    private BigDecimal low;

    @Column(name = "close_price", nullable = false, precision = 19, scale = 6)
    private BigDecimal close;

    @Column(name = "volume", nullable = false, precision = 19, scale = 4)
    private BigDecimal volume = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private MarketDataSource source = MarketDataSource.SIMULATED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public MarketCandle() {
    }

    public MarketCandle(Long instrumentId,
                        Timeframe timeframe,
                        Instant openTime,
                        BigDecimal open,
                        BigDecimal high,
                        BigDecimal low,
                        BigDecimal close,
                        BigDecimal volume,
                        MarketDataSource source) {
        this.instrumentId = instrumentId;
        this.timeframe = timeframe;
        this.openTime = openTime;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.source = source;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getInstrumentId() {
        return instrumentId;
    }

    public void setInstrumentId(Long instrumentId) {
        this.instrumentId = instrumentId;
    }

    public Timeframe getTimeframe() {
        return timeframe;
    }

    public void setTimeframe(Timeframe timeframe) {
        this.timeframe = timeframe;
    }

    public Instant getOpenTime() {
        return openTime;
    }

    public void setOpenTime(Instant openTime) {
        this.openTime = openTime;
    }

    public BigDecimal getOpen() {
        return open;
    }

    public void setOpen(BigDecimal open) {
        this.open = open;
    }

    public BigDecimal getHigh() {
        return high;
    }

    public void setHigh(BigDecimal high) {
        this.high = high;
    }

    public BigDecimal getLow() {
        return low;
    }

    public void setLow(BigDecimal low) {
        this.low = low;
    }

    public BigDecimal getClose() {
        return close;
    }

    public void setClose(BigDecimal close) {
        this.close = close;
    }

    public BigDecimal getVolume() {
        return volume;
    }

    public void setVolume(BigDecimal volume) {
        this.volume = volume;
    }

    public MarketDataSource getSource() {
        return source;
    }

    public void setSource(MarketDataSource source) {
        this.source = source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

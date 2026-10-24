package com.tradex.entity;

import com.tradex.enums.AssetClass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A tradable / chartable instrument.
 *
 * <p>{@code providerSymbol} carries the provider-specific identifier (for example
 * {@code RELIANCE} on {@code NSE} for Twelve Data, or {@code BINANCE:BTCUSDT} for Finnhub).
 * Instruments whose symbol cannot be mapped on the currently configured provider are marked
 * {@code providerSupported = false} and are surfaced to the UI as unsupported instead of
 * being silently faked.</p>
 */
@Entity
@Table(
        name = "instruments",
        uniqueConstraints = @UniqueConstraint(name = "uk_instruments_symbol", columnNames = "symbol"),
        indexes = {
                @Index(name = "idx_instruments_asset_class", columnList = "asset_class"),
                @Index(name = "idx_instruments_active", columnList = "active")
        })
public class Instrument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", nullable = false, length = 40)
    private String symbol;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "exchange", nullable = false, length = 40)
    private String exchange;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_class", nullable = false, length = 20)
    private AssetClass assetClass;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency = "INR";

    @Column(name = "settlement_currency", nullable = false, length = 8)
    private String settlementCurrency;

    /** Name of the provider adapter that can serve this instrument, e.g. {@code SIMULATED}. */
    @Column(name = "data_provider", nullable = false, length = 40)
    private String dataProvider = "SIMULATED";

    /** Provider specific ticker, e.g. {@code NSE:RELIANCE} or {@code BTC/INR}. */
    @Column(name = "provider_symbol", length = 80)
    private String providerSymbol;

    @Column(name = "provider_supported", nullable = false)
    private boolean providerSupported = true;

    @Column(name = "tick_size", nullable = false, precision = 19, scale = 8)
    private BigDecimal tickSize = new BigDecimal("0.05");

    @Column(name = "lot_size", nullable = false, precision = 19, scale = 8)
    private BigDecimal lotSize = BigDecimal.ONE;

    /** Indices are chartable but not orderable. */
    @Column(name = "tradable", nullable = false)
    private boolean tradable = true;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    /** Anchor price used to bootstrap the simulated price process. Never used for live data. */
    @Column(name = "seed_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal seedPrice = BigDecimal.TEN;

    /** Daily volatility, expressed as a fraction of price (0.02 == 2%). */
    @Column(name = "volatility", nullable = false, precision = 10, scale = 6)
    private BigDecimal volatility = new BigDecimal("0.020000");

    @Column(name = "sector", length = 60)
    private String sector;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (settlementCurrency == null || settlementCurrency.isBlank()) {
            settlementCurrency = currency;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public AssetClass getAssetClass() {
        return assetClass;
    }

    public void setAssetClass(AssetClass assetClass) {
        this.assetClass = assetClass;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getSettlementCurrency() {
        return settlementCurrency == null || settlementCurrency.isBlank() ? currency : settlementCurrency;
    }

    public void setSettlementCurrency(String settlementCurrency) {
        this.settlementCurrency = settlementCurrency;
    }

    public String getDataProvider() {
        return dataProvider;
    }

    public void setDataProvider(String dataProvider) {
        this.dataProvider = dataProvider;
    }

    public String getProviderSymbol() {
        return providerSymbol;
    }

    public void setProviderSymbol(String providerSymbol) {
        this.providerSymbol = providerSymbol;
    }

    public boolean isProviderSupported() {
        return providerSupported;
    }

    public void setProviderSupported(boolean providerSupported) {
        this.providerSupported = providerSupported;
    }

    public BigDecimal getTickSize() {
        return tickSize;
    }

    public void setTickSize(BigDecimal tickSize) {
        this.tickSize = tickSize;
    }

    public BigDecimal getLotSize() {
        return lotSize;
    }

    public void setLotSize(BigDecimal lotSize) {
        this.lotSize = lotSize;
    }

    public boolean isTradable() {
        return tradable;
    }

    public void setTradable(boolean tradable) {
        this.tradable = tradable;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public BigDecimal getSeedPrice() {
        return seedPrice;
    }

    public void setSeedPrice(BigDecimal seedPrice) {
        this.seedPrice = seedPrice;
    }

    public BigDecimal getVolatility() {
        return volatility;
    }

    public void setVolatility(BigDecimal volatility) {
        this.volatility = volatility;
    }

    public String getSector() {
        return sector;
    }

    public void setSector(String sector) {
        this.sector = sector;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

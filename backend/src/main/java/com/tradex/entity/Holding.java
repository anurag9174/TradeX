package com.tradex.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Long position in one instrument, carried at <strong>average cost</strong>.
 *
 * <p>Buying adds quantity and re-weights {@code avgCost}; selling reduces quantity and books
 * realised P&amp;L as {@code (fillPrice - avgCost) * qty - sellCharges} without changing
 * {@code avgCost}. A holding that reaches zero quantity is deleted so the portfolio never
 * shows dust positions.</p>
 */
@Entity
@Table(
        name = "holdings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_holdings_account_instrument",
                columnNames = {"account_id", "instrument_id"}),
        indexes = @Index(name = "idx_holdings_account", columnList = "account_id"))
public class Holding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "avg_cost", nullable = false, precision = 19, scale = 6)
    private BigDecimal avgCost = BigDecimal.ZERO;

    /** Total buy-side charges capitalised into this position. */
    @Column(name = "accumulated_buy_charges", nullable = false, precision = 19, scale = 4)
    private BigDecimal accumulatedBuyCharges = BigDecimal.ZERO;

    @Column(name = "realized_pnl", nullable = false, precision = 19, scale = 4)
    private BigDecimal realizedPnl = BigDecimal.ZERO;

    /** Optional protective stop attached to this position (risk-management helper). */
    @Column(name = "stop_loss_price", precision = 19, scale = 4)
    private BigDecimal stopLossPrice;

    /** Optional profit target attached to this position. */
    @Column(name = "target_price", precision = 19, scale = 4)
    private BigDecimal targetPrice;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "last_traded_at")
    private Instant lastTradedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (openedAt == null) {
            openedAt = now;
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

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Long getInstrumentId() {
        return instrumentId;
    }

    public void setInstrumentId(Long instrumentId) {
        this.instrumentId = instrumentId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getAvgCost() {
        return avgCost;
    }

    public void setAvgCost(BigDecimal avgCost) {
        this.avgCost = avgCost;
    }

    public BigDecimal getAccumulatedBuyCharges() {
        return accumulatedBuyCharges;
    }

    public void setAccumulatedBuyCharges(BigDecimal accumulatedBuyCharges) {
        this.accumulatedBuyCharges = accumulatedBuyCharges;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    public void setRealizedPnl(BigDecimal realizedPnl) {
        this.realizedPnl = realizedPnl;
    }

    public BigDecimal getStopLossPrice() {
        return stopLossPrice;
    }

    public void setStopLossPrice(BigDecimal stopLossPrice) {
        this.stopLossPrice = stopLossPrice;
    }

    public BigDecimal getTargetPrice() {
        return targetPrice;
    }

    public void setTargetPrice(BigDecimal targetPrice) {
        this.targetPrice = targetPrice;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(Instant openedAt) {
        this.openedAt = openedAt;
    }

    public Instant getLastTradedAt() {
        return lastTradedAt;
    }

    public void setLastTradedAt(Instant lastTradedAt) {
        this.lastTradedAt = lastTradedAt;
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

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}

package com.tradex.entity;

import com.tradex.enums.OrderSide;
import com.tradex.enums.OrderStatus;
import com.tradex.enums.OrderType;
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
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/** A virtual order. Belongs to exactly one trading account. */
@Entity
@Table(
        name = "orders",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_orders_ref", columnNames = "order_ref"),
                @UniqueConstraint(name = "uk_orders_idempotency", columnNames = "idempotency_key")
        },
        indexes = {
                @Index(name = "idx_orders_account_status", columnList = "account_id,status"),
                @Index(name = "idx_orders_account_created", columnList = "account_id,created_at"),
                @Index(name = "idx_orders_working", columnList = "status,order_type"),
                @Index(name = "idx_orders_instrument", columnList = "instrument_id")
        })
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public, non-guessable reference shown in the UI (e.g. {@code TX-3F9A21C4}). */
    @Column(name = "order_ref", nullable = false, length = 40)
    private String orderRef;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 8)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 16)
    private OrderType orderType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;

    /** Required for LIMIT and STOP_LOSS orders. */
    @Column(name = "limit_price", precision = 19, scale = 4)
    private BigDecimal limitPrice;

    /** Trigger price for STOP_LOSS orders. */
    @Column(name = "stop_price", precision = 19, scale = 4)
    private BigDecimal stopPrice;

    /** Protective stop attached to the resulting position. Optional, informational. */
    @Column(name = "position_stop_loss", precision = 19, scale = 4)
    private BigDecimal positionStopLoss;

    /** Profit target attached to the resulting position. Optional, informational. */
    @Column(name = "position_target", precision = 19, scale = 4)
    private BigDecimal positionTarget;

    @Column(name = "filled_quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal filledQuantity = BigDecimal.ZERO;

    @Column(name = "avg_fill_price", precision = 19, scale = 4)
    private BigDecimal avgFillPrice;

    /** Cash locked while this order rests. Released/consumed on terminal transition. */
    @Column(name = "reserved_cash", nullable = false, precision = 19, scale = 4)
    private BigDecimal reservedCash = BigDecimal.ZERO;

    /** Estimated charges at submission time. */
    @Column(name = "estimated_charges", nullable = false, precision = 19, scale = 4)
    private BigDecimal estimatedCharges = BigDecimal.ZERO;

    /** Actual charges booked on execution. */
    @Column(name = "charges", nullable = false, precision = 19, scale = 4)
    private BigDecimal charges = BigDecimal.ZERO;

    @Column(name = "realized_pnl", nullable = false, precision = 19, scale = 4)
    private BigDecimal realizedPnl = BigDecimal.ZERO;

    /** Net cash impact (negative = cash out) at execution price. */
    @Column(name = "net_cash_impact", nullable = false, precision = 19, scale = 4)
    private BigDecimal netCashImpact = BigDecimal.ZERO;

    @Column(name = "reject_reason", length = 255)
    private String rejectReason;

    @Column(name = "idempotency_key", length = 80)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** Set when a stop order has triggered and is being worked as a market order. */
    @Column(name = "triggered_at")
    private Instant triggeredAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
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

    public String getOrderRef() {
        return orderRef;
    }

    public void setOrderRef(String orderRef) {
        this.orderRef = orderRef;
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

    public OrderSide getSide() {
        return side;
    }

    public void setSide(OrderSide side) {
        this.side = side;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public void setOrderType(OrderType orderType) {
        this.orderType = orderType;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getLimitPrice() {
        return limitPrice;
    }

    public void setLimitPrice(BigDecimal limitPrice) {
        this.limitPrice = limitPrice;
    }

    public BigDecimal getStopPrice() {
        return stopPrice;
    }

    public void setStopPrice(BigDecimal stopPrice) {
        this.stopPrice = stopPrice;
    }

    public BigDecimal getPositionStopLoss() {
        return positionStopLoss;
    }

    public void setPositionStopLoss(BigDecimal positionStopLoss) {
        this.positionStopLoss = positionStopLoss;
    }

    public BigDecimal getPositionTarget() {
        return positionTarget;
    }

    public void setPositionTarget(BigDecimal positionTarget) {
        this.positionTarget = positionTarget;
    }

    public BigDecimal getFilledQuantity() {
        return filledQuantity;
    }

    public void setFilledQuantity(BigDecimal filledQuantity) {
        this.filledQuantity = filledQuantity;
    }

    public BigDecimal getAvgFillPrice() {
        return avgFillPrice;
    }

    public void setAvgFillPrice(BigDecimal avgFillPrice) {
        this.avgFillPrice = avgFillPrice;
    }

    public BigDecimal getReservedCash() {
        return reservedCash;
    }

    public void setReservedCash(BigDecimal reservedCash) {
        this.reservedCash = reservedCash;
    }

    public BigDecimal getEstimatedCharges() {
        return estimatedCharges;
    }

    public void setEstimatedCharges(BigDecimal estimatedCharges) {
        this.estimatedCharges = estimatedCharges;
    }

    public BigDecimal getCharges() {
        return charges;
    }

    public void setCharges(BigDecimal charges) {
        this.charges = charges;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    public void setRealizedPnl(BigDecimal realizedPnl) {
        this.realizedPnl = realizedPnl;
    }

    public BigDecimal getNetCashImpact() {
        return netCashImpact;
    }

    public void setNetCashImpact(BigDecimal netCashImpact) {
        this.netCashImpact = netCashImpact;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
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

    public Instant getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(Instant executedAt) {
        this.executedAt = executedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public Instant getTriggeredAt() {
        return triggeredAt;
    }

    public void setTriggeredAt(Instant triggeredAt) {
        this.triggeredAt = triggeredAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}

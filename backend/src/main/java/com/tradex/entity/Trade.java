package com.tradex.entity;

import com.tradex.enums.OrderSide;
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
 * An executed fill. Trades are append-only: one row per execution, referencing the order that
 * produced it. {@code executionKey} is uniquely constrained so a retried execution can never
 * double-book a fill.
 */
@Entity
@Table(
        name = "trades",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_trades_ref", columnNames = "trade_ref"),
                @UniqueConstraint(name = "uk_trades_execution_key", columnNames = "execution_key")
        },
        indexes = {
                @Index(name = "idx_trades_account_time", columnList = "account_id,executed_at"),
                @Index(name = "idx_trades_account_instrument", columnList = "account_id,instrument_id"),
                @Index(name = "idx_trades_order", columnList = "order_id")
        })
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_ref", nullable = false, length = 40)
    private String tradeRef;

    /** Deterministic idempotency token, e.g. {@code 42:B1} (order 42, first buy fill). */
    @Column(name = "execution_key", nullable = false, length = 80)
    private String executionKey;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 8)
    private OrderSide side;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;

    @Column(name = "price", nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    /** quantity * price, before charges. */
    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal grossAmount = BigDecimal.ZERO;

    @Column(name = "charges", nullable = false, precision = 19, scale = 4)
    private BigDecimal charges = BigDecimal.ZERO;

    /** Signed cash movement: -(gross+charges) for buys, +(gross-charges) for sells. */
    @Column(name = "net_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal netAmount = BigDecimal.ZERO;

    /** Realised P&L attributed to this fill. Always zero for buys. */
    @Column(name = "realized_pnl", nullable = false, precision = 19, scale = 4)
    private BigDecimal realizedPnl = BigDecimal.ZERO;

    /** Quantity-weighted average cost of the position *before* this fill. */
    @Column(name = "cost_basis", precision = 19, scale = 6)
    private BigDecimal costBasis;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTradeRef() {
        return tradeRef;
    }

    public void setTradeRef(String tradeRef) {
        this.tradeRef = tradeRef;
    }

    public String getExecutionKey() {
        return executionKey;
    }

    public void setExecutionKey(String executionKey) {
        this.executionKey = executionKey;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
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

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getGrossAmount() {
        return grossAmount;
    }

    public void setGrossAmount(BigDecimal grossAmount) {
        this.grossAmount = grossAmount;
    }

    public BigDecimal getCharges() {
        return charges;
    }

    public void setCharges(BigDecimal charges) {
        this.charges = charges;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public void setNetAmount(BigDecimal netAmount) {
        this.netAmount = netAmount;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    public void setRealizedPnl(BigDecimal realizedPnl) {
        this.realizedPnl = realizedPnl;
    }

    public BigDecimal getCostBasis() {
        return costBasis;
    }

    public void setCostBasis(BigDecimal costBasis) {
        this.costBasis = costBasis;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(Instant executedAt) {
        this.executedAt = executedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

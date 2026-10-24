package com.tradex.entity;

import com.tradex.enums.LedgerEntryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Append-only record of every movement of virtual cash.
 *
 * <p>The entity is annotated {@link Immutable}, so Hibernate never issues UPDATEs against it.
 * Rows are only ever inserted, which makes the ledger a faithful audit trail and lets the
 * balance be reconciled at any time: {@code cashBalance + reservedCash} must equal the running
 * {@code balanceAfter + reservedAfter} of the last entry for the account.</p>
 */
@Entity
@Table(
        name = "transaction_ledger",
        indexes = {
                @Index(name = "idx_ledger_account_time", columnList = "account_id,created_at"),
                @Index(name = "idx_ledger_account_type", columnList = "account_id,entry_type"),
                @Index(name = "idx_ledger_order", columnList = "order_id"),
                @Index(name = "idx_ledger_trade", columnList = "trade_id")
        })
@Immutable
public class TransactionLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 30)
    private LedgerEntryType entryType;

    /** Signed change applied to spendable cash. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** Signed change applied to reserved cash. */
    @Column(name = "reserved_delta", nullable = false, precision = 19, scale = 4)
    private BigDecimal reservedDelta = BigDecimal.ZERO;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @Column(name = "reserved_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal reservedAfter;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "trade_id")
    private Long tradeId;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TransactionLedger() {
    }

    public TransactionLedger(Long accountId,
                             String currency,
                             LedgerEntryType entryType,
                             BigDecimal amount,
                             BigDecimal reservedDelta,
                             BigDecimal balanceAfter,
                             BigDecimal reservedAfter,
                             Long orderId,
                             Long tradeId,
                             String description) {
        this.accountId = accountId;
        this.currency = currency;
        this.entryType = entryType;
        this.amount = amount;
        this.reservedDelta = reservedDelta == null ? BigDecimal.ZERO : reservedDelta;
        this.balanceAfter = balanceAfter;
        this.reservedAfter = reservedAfter;
        this.orderId = orderId;
        this.tradeId = tradeId;
        this.description = description;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getCurrency() {
        return currency;
    }

    public LedgerEntryType getEntryType() {
        return entryType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getReservedDelta() {
        return reservedDelta;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public BigDecimal getReservedAfter() {
        return reservedAfter;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getTradeId() {
        return tradeId;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

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
import java.time.LocalDate;

/**
 * One virtual trading account. Every registered user gets exactly one, funded with
 * {@code initialBalance} through a single
 * {@code INITIAL_GRANT} ledger entry.
 */
@Entity
@Table(
        name = "trading_accounts",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_trading_accounts_user", columnNames = "user_id"),
                @UniqueConstraint(name = "uk_trading_accounts_number", columnNames = "account_number")
        },
        indexes = @Index(name = "idx_trading_accounts_user", columnList = "user_id"))
public class TradingAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "account_number", nullable = false, length = 40)
    private String accountNumber;

    @Column(name = "display_name", length = 80)
    private String displayName;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    /** The fixed notional the account is funded with. */
    @Column(name = "initial_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal initialBalance = BigDecimal.ZERO;

    /** Spendable virtual cash. Never allowed to go negative. */
    @Column(name = "cash_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal cashBalance = BigDecimal.ZERO;

    /** Cash locked behind resting buy orders. Still owned by the user, not spendable. */
    @Column(name = "reserved_cash", nullable = false, precision = 19, scale = 4)
    private BigDecimal reservedCash = BigDecimal.ZERO;

    /** Lifetime realised P&L (net of charges) booked when selling. */
    @Column(name = "realized_pnl", nullable = false, precision = 19, scale = 4)
    private BigDecimal realizedPnl = BigDecimal.ZERO;

    /** Lifetime charges paid. */
    @Column(name = "total_charges", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalCharges = BigDecimal.ZERO;

    /** Maximum simulated loss allowed in one trading day. Zero disables the guard. */
    @Column(name = "daily_loss_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal dailyLossLimit = BigDecimal.ZERO;

    @Column(name = "daily_loss_limit_enabled", nullable = false)
    private boolean dailyLossLimitEnabled = false;

    /** Trading day on which the loss limit was breached. Blocks trading until reset / next day. */
    @Column(name = "loss_limit_breach_date")
    private LocalDate lossLimitBreachDate;

    /** Hard switch: when true all new orders are rejected. */
    @Column(name = "trading_blocked", nullable = false)
    private boolean tradingBlocked = false;

    @Column(name = "reset_count", nullable = false)
    private int resetCount = 0;

    @Column(name = "last_reset_at")
    private Instant lastResetAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic lock guard. Combined with the pessimistic row lock taken by the execution
     * engine this prevents lost updates and double spends under concurrent requests.
     */
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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getInitialBalance() {
        return initialBalance;
    }

    public void setInitialBalance(BigDecimal initialBalance) {
        this.initialBalance = initialBalance;
    }

    public BigDecimal getCashBalance() {
        return cashBalance;
    }

    public void setCashBalance(BigDecimal cashBalance) {
        this.cashBalance = cashBalance;
    }

    public BigDecimal getReservedCash() {
        return reservedCash;
    }

    public void setReservedCash(BigDecimal reservedCash) {
        this.reservedCash = reservedCash;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    public void setRealizedPnl(BigDecimal realizedPnl) {
        this.realizedPnl = realizedPnl;
    }

    public BigDecimal getTotalCharges() {
        return totalCharges;
    }

    public void setTotalCharges(BigDecimal totalCharges) {
        this.totalCharges = totalCharges;
    }

    public BigDecimal getDailyLossLimit() {
        return dailyLossLimit;
    }

    public void setDailyLossLimit(BigDecimal dailyLossLimit) {
        this.dailyLossLimit = dailyLossLimit;
    }

    public boolean isDailyLossLimitEnabled() {
        return dailyLossLimitEnabled;
    }

    public void setDailyLossLimitEnabled(boolean dailyLossLimitEnabled) {
        this.dailyLossLimitEnabled = dailyLossLimitEnabled;
    }

    public LocalDate getLossLimitBreachDate() {
        return lossLimitBreachDate;
    }

    public void setLossLimitBreachDate(LocalDate lossLimitBreachDate) {
        this.lossLimitBreachDate = lossLimitBreachDate;
    }

    public boolean isTradingBlocked() {
        return tradingBlocked;
    }

    public void setTradingBlocked(boolean tradingBlocked) {
        this.tradingBlocked = tradingBlocked;
    }

    public int getResetCount() {
        return resetCount;
    }

    public void setResetCount(int resetCount) {
        this.resetCount = resetCount;
    }

    public Instant getLastResetAt() {
        return lastResetAt;
    }

    public void setLastResetAt(Instant lastResetAt) {
        this.lastResetAt = lastResetAt;
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

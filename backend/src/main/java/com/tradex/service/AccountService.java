package com.tradex.service;

import com.tradex.config.TradingProperties;
import com.tradex.dto.account.AccountDto;
import com.tradex.dto.account.AccountSummaryDto;
import com.tradex.dto.account.BalanceDto;
import com.tradex.dto.account.ResetAccountRequest;
import com.tradex.dto.account.ResetAccountResponse;
import com.tradex.entity.TradingAccount;
import com.tradex.entity.User;
import com.tradex.enums.LedgerEntryType;
import com.tradex.enums.OrderStatus;
import com.tradex.exception.BadRequestException;
import com.tradex.exception.ResourceNotFoundException;
import com.tradex.exception.TradingBlockedException;
import com.tradex.repository.HoldingRepository;
import com.tradex.repository.OrderRepository;
import com.tradex.repository.TradeRepository;
import com.tradex.repository.TradingAccountRepository;
import com.tradex.repository.TransactionLedgerRepository;
import com.tradex.trading.Decimals;
import com.tradex.trading.LedgerService;
import com.tradex.trading.PortfolioService;
import com.tradex.trading.Refs;
import com.tradex.trading.RiskGuardService;
import com.tradex.trading.TradingClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Owns the virtual trading account lifecycle: opening it, reading it and restarting it. */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final TradingAccountRepository accountRepository;
    private final HoldingRepository holdingRepository;
    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final TransactionLedgerRepository ledgerRepository;
    private final LedgerService ledgerService;
    private final PortfolioService portfolioService;
    private final RiskGuardService riskGuardService;
    private final TradingClock clock;
    private final TradingProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    public AccountService(TradingAccountRepository accountRepository,
                          HoldingRepository holdingRepository,
                          OrderRepository orderRepository,
                          TradeRepository tradeRepository,
                          TransactionLedgerRepository ledgerRepository,
                          LedgerService ledgerService,
                          PortfolioService portfolioService,
                          RiskGuardService riskGuardService,
                          TradingClock clock,
                          TradingProperties properties,
                          ApplicationEventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.ledgerRepository = ledgerRepository;
        this.ledgerService = ledgerService;
        this.portfolioService = portfolioService;
        this.riskGuardService = riskGuardService;
        this.clock = clock;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    // =======================================================================================
    // Opening
    // =======================================================================================

    /**
     * Opens the user's single virtual account and funds it with exactly one {@code INITIAL_GRANT}
     * ledger entry.
     *
     * <p>Idempotent and safe under concurrency: the {@code uk_trading_accounts_user} unique
     * constraint makes a second account impossible, and the grant is only written when the account
     * was genuinely created in this call.</p>
     */
    @Transactional
    public TradingAccount openAccount(User user) {
        return openAccount(user, null);
    }

    @Transactional
    public TradingAccount openAccount(User user, BigDecimal requestedInitialCapital) {
        BigDecimal opening = requestedInitialCapital == null
                ? validateInitialCapital(properties.getInitialBalance())
                : validateInitialCapital(requestedInitialCapital);
        return accountRepository.findByUserId(user.getId()).orElseGet(() -> {
            TradingAccount account = new TradingAccount();
            account.setUserId(user.getId());
            account.setAccountNumber(Refs.account(user.getUsername()));
            account.setDisplayName("Virtual Trading Account");
            account.setCurrency(properties.getDefaultAccountCurrency());
            account.setInitialBalance(opening);
            // Cash starts at zero; LedgerService is the single writer, so the account balance and
            // the immutable INITIAL_GRANT row can never diverge.
            account.setCashBalance(BigDecimal.ZERO);
            account.setReservedCash(BigDecimal.ZERO);
            account.setRealizedPnl(BigDecimal.ZERO);
            account.setTotalCharges(BigDecimal.ZERO);
            account.setDailyLossLimit(BigDecimal.ZERO);
            account.setDailyLossLimitEnabled(false);
            account.setTradingBlocked(false);
            account.setResetCount(0);
            TradingAccount saved = accountRepository.saveAndFlush(account);

            ledgerService.creditCash(saved, LedgerEntryType.INITIAL_GRANT, opening, null, null,
                    "Initial virtual balance grant of " + opening.toPlainString() + " "
                            + saved.getCurrency());
            TradingAccount persisted = accountRepository.saveAndFlush(saved);
            log.info("Opened virtual account {} for user {} with balance {}",
                    persisted.getAccountNumber(), user.getUsername(), opening.toPlainString());
            return persisted;
        });
    }

    /** Back-fills the grant for accounts created before the ledger existed (defensive). */
    @Transactional
    public TradingAccount ensureGrant(TradingAccount account) {
        if (ledgerRepository.countInitialGrants(account.getId()) == 0) {
            ledgerService.creditCash(account, LedgerEntryType.INITIAL_GRANT, account.getInitialBalance(),
                    null, null, "Initial virtual balance grant (back-filled)");
            return accountRepository.saveAndFlush(account);
        }
        return account;
    }

    // =======================================================================================
    // Reading
    // =======================================================================================

    @Transactional(readOnly = true)
    public TradingAccount requireAccount(Long userId) {
        return accountRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("ACCOUNT_NOT_FOUND",
                        "No trading account exists for this user."));
    }

    @Transactional(readOnly = true)
    public AccountDto account(Long userId) {
        TradingAccount account = requireAccount(userId);
        PortfolioService.Valuation valuation = portfolioService.value(account);
        BigDecimal todayNet = valuation.todaysPnl();
        boolean breached = riskGuardService.isLimitBreached(account, todayNet);
        boolean blocked = account.isTradingBlocked() || breached;

        return new AccountDto(
                account.getId(),
                account.getAccountNumber(),
                account.getDisplayName(),
                account.getCurrency(),
                Decimals.money(account.getInitialBalance()),
                valuation.availableCash(),
                valuation.reservedCash(),
                valuation.totalCash(),
                valuation.holdingsValue(),
                valuation.portfolioValue(),
                valuation.investedValue(),
                valuation.unrealizedPnl(),
                scale(valuation.unrealizedPnlPercent()),
                valuation.realizedPnl(),
                Decimals.money(Decimals.nullToZero(account.getTotalCharges())),
                valuation.totalReturn(),
                scale(valuation.totalReturnPercent()),
                valuation.todaysPnl(),
                valuation.positions().size(),
                valuation.openOrderCount(),
                account.isDailyLossLimitEnabled(),
                Decimals.money(Decimals.nullToZero(account.getDailyLossLimit())),
                blocked,
                blocked ? riskGuardService.blockedReason(account, todayNet) : null,
                account.getResetCount(),
                account.getLastResetAt(),
                account.getCreatedAt());
    }

    /** Account snapshot addressed by account id; used by the WebSocket listener. */
    @Transactional(readOnly = true)
    public AccountDto accountByAccountId(Long accountId) {
        TradingAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("ACCOUNT_NOT_FOUND",
                        "No trading account with id " + accountId + "."));
        return account(account.getUserId());
    }

    @Transactional(readOnly = true)
    public BalanceDto balance(Long userId) {
        TradingAccount account = requireAccount(userId);
        return new BalanceDto(
                account.getAccountNumber(),
                account.getCurrency(),
                Decimals.money(account.getCashBalance()),
                Decimals.money(account.getReservedCash()),
                Decimals.money(account.getCashBalance().add(account.getReservedCash())),
                Decimals.money(account.getInitialBalance()));
    }

    @Transactional(readOnly = true)
    public AccountSummaryDto summary(Long userId) {
        TradingAccount account = requireAccount(userId);
        PortfolioService.Valuation valuation = portfolioService.value(account);
        return new AccountSummaryDto(
                account.getCurrency(),
                portfolioService.fxNote(valuation),
                valuation.availableCash(),
                Decimals.money(account.getInitialBalance()),
                valuation.portfolioValue(),
                valuation.investedValue(),
                valuation.unrealizedPnl(),
                valuation.realizedPnl(),
                scale(valuation.totalReturnPercent()),
                valuation.positions().size(),
                valuation.todaysPnl(),
                valuation.totalReturn(),
                valuation.investedValue(),
                valuation.holdingsValue(),
                valuation.reservedCash(),
                valuation.openOrderCount(),
                valuation.tradeCountToday(),
                valuation.todaysCharges());
    }

    // =======================================================================================
    // Reset
    // =======================================================================================

    /**
     * Restarts the simulated account.
     *
     * <p>What happens, in one transaction:</p>
     * <ol>
     *   <li>every working order is cancelled and its reservation released;</li>
     *   <li>holdings, orders and trades are removed, so the next session starts from a clean book;</li>
     *   <li>cash and reserved cash are set to exactly the account's opening balance;</li>
     *   <li>an {@code ACCOUNT_RESET} ledger entry records the movement - the ledger itself is
     *       append-only and is never rewritten, so the audit trail survives;</li>
     *   <li>realised P&amp;L, charges, the daily-loss breach and the trading block are cleared.</li>
     * </ol>
     *
     * <p>The caller must explicitly confirm; a bare POST without {@code confirm: true} is rejected so
     * a stray request cannot wipe a portfolio.</p>
     */
    @Transactional
    public ResetAccountResponse reset(Long userId, ResetAccountRequest request) {
        if (request == null || !request.isConfirmed()) {
            throw new BadRequestException("RESET_NOT_CONFIRMED",
                    "Account reset must be explicitly confirmed by sending {\"confirm\": true}.");
        }
        if (request.hasDailyLossLimit() && request.dailyLossLimit().signum() < 0) {
            throw new BadRequestException("INVALID_LOSS_LIMIT", "Daily loss limit cannot be negative.");
        }
        TradingAccount account = accountRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("ACCOUNT_NOT_FOUND",
                        "No trading account exists for this user."));

        String previousCurrency = account.getCurrency();
        PortfolioService.Valuation before = portfolioService.value(account);
        String newCurrency = properties.getDefaultAccountCurrency();
        BigDecimal newInitialCapital = request.hasInitialCapital()
                ? validateInitialCapital(request.initialCapital())
                : validateInitialCapital(properties.getInitialBalance());
        boolean currencyChanged = !account.getCurrency().equalsIgnoreCase(newCurrency);
        BigDecimal previousCash = Decimals.money(account.getCashBalance());
        BigDecimal previousHoldings = before.holdingsValue();
        int positions = before.positions().size();

        List<com.tradex.entity.Order> working = orderRepository.searchAll(account.getId(),
                List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED),
                null, null, null, null);
        Instant now = Instant.now();
        for (com.tradex.entity.Order order : working) {
            BigDecimal reserved = Decimals.nullToZero(order.getReservedCash());
            if (reserved.signum() > 0) {
                ledgerService.releaseReservedCash(account, reserved, order.getId(),
                        "Reservation released by account reset (" + order.getOrderRef() + ")");
                order.setReservedCash(BigDecimal.ZERO);
            }
            order.setStatus(OrderStatus.CANCELLED);
            order.setCancelledAt(now);
            orderRepository.save(order);
        }

        int accountTrades = tradeRepository.searchAll(account.getId(), null, null, null, null).size();

        holdingRepository.deleteByAccountId(account.getId());
        tradeRepository.deleteByAccountId(account.getId());
        orderRepository.deleteByAccountId(account.getId());
        holdingRepository.flush();
        tradeRepository.flush();
        orderRepository.flush();

        // Absolute values, recorded against the immutable ledger.
        ledgerService.setReservation(account, BigDecimal.ZERO, null, "Reservation cleared by account reset");
        account.setInitialBalance(newInitialCapital);
        if (currencyChanged) {
            ledgerService.startNewCurrencySession(account, newCurrency, newInitialCapital,
                    "New " + newCurrency + " virtual session opened by confirmed account reset");
        } else {
            ledgerService.setBalance(account, LedgerEntryType.ACCOUNT_RESET, newInitialCapital, null,
                    "Account reset to the selected initial virtual balance");
        }

        account.setRealizedPnl(BigDecimal.ZERO);
        account.setTotalCharges(BigDecimal.ZERO);
        account.setResetCount(account.getResetCount() + 1);
        account.setLastResetAt(now);
        riskGuardService.clearBreach(account);
        if (currencyChanged) {
            account.setDailyLossLimit(BigDecimal.ZERO);
            account.setDailyLossLimitEnabled(false);
        }
        if (request.hasDailyLossLimit()) {
            account.setDailyLossLimit(Decimals.money(request.dailyLossLimit()));
        }
        if (request.hasDailyLossLimitEnabled()) {
            account.setDailyLossLimitEnabled(request.dailyLossLimitEnabled());
        }
        TradingAccount saved = accountRepository.saveAndFlush(account);

        eventPublisher.publishEvent(new AccountChangedEvent(saved.getId(), "ACCOUNT_RESET"));
        log.info("Account {} reset: {} positions, {} trades removed; balance restored to {}",
                saved.getAccountNumber(), positions, accountTrades, saved.getInitialBalance().toPlainString());

        return new ResetAccountResponse(
                saved.getAccountNumber(),
                saved.getCurrency(),
                Decimals.money(saved.getCashBalance()),
                previousCurrency,
                previousCash,
                previousHoldings,
                positions,
                accountTrades,
                working.size(),
                saved.getResetCount(),
                now,
                "Simulated account restarted with "
                        + Decimals.money(saved.getCashBalance()).toPlainString() + " "
                        + saved.getCurrency() + ". Holdings, orders and trade history were cleared; "
                        + "the transaction ledger keeps the audit trail.");
    }

    /** Applies new risk settings to the account. */
    @Transactional
    public TradingAccount updateRiskSettings(Long userId, BigDecimal dailyLossLimit,
                                             Boolean enabled, Boolean blocked, boolean clearBreach) {
        TradingAccount account = accountRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("ACCOUNT_NOT_FOUND",
                        "No trading account exists for this user."));
        if (dailyLossLimit != null) {
            if (dailyLossLimit.signum() < 0) {
                throw new BadRequestException("INVALID_LOSS_LIMIT", "Daily loss limit cannot be negative.");
            }
            account.setDailyLossLimit(Decimals.money(dailyLossLimit));
        }
        if (enabled != null) {
            account.setDailyLossLimitEnabled(enabled);
        }
        if (blocked != null) {
            account.setTradingBlocked(blocked);
        }
        if (clearBreach) {
            riskGuardService.clearBreach(account);
        }
        return accountRepository.saveAndFlush(account);
    }

    /** Guard used before accepting an order. Re-reads the day's result. */
    public void assertTradingAllowed(TradingAccount account) {
        BigDecimal todayNet = portfolioService.todaysNetPnl(account);
        if (account.isTradingBlocked()) {
            throw new TradingBlockedException(riskGuardService.blockedReason(account, todayNet));
        }
        riskGuardService.enforceCanTrade(account, todayNet);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null
                : value.setScale(4, java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal validateInitialCapital(BigDecimal amount) {
        if (amount == null || amount.compareTo(properties.getMinimumInitialBalance()) < 0
                || amount.compareTo(properties.getMaximumInitialBalance()) > 0
                || amount.scale() > 2) {
            throw new BadRequestException("INVALID_VIRTUAL_CAPITAL",
                    "Virtual capital must be between "
                            + properties.getMinimumInitialBalance().toPlainString() + " and "
                            + properties.getMaximumInitialBalance().toPlainString() + " "
                            + properties.getDefaultAccountCurrency() + ", with at most 2 decimal places.");
        }
        return Decimals.money(amount);
    }
}

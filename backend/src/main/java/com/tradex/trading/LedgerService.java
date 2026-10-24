package com.tradex.trading;

import com.tradex.entity.TradingAccount;
import com.tradex.entity.TransactionLedger;
import com.tradex.enums.LedgerEntryType;
import com.tradex.repository.TransactionLedgerRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Writes the immutable cash ledger.
 *
 * <p>Every method both mutates the account's cash fields <em>and</em> appends the matching ledger
 * row, so the two can never diverge. Callers must already hold the account's pessimistic write lock
 * (see {@code TradingAccountRepository#findByIdForUpdate}).</p>
 */
@Service
public class LedgerService {

    private final TransactionLedgerRepository ledgerRepository;

    public LedgerService(TransactionLedgerRepository ledgerRepository) {
        this.ledgerRepository = ledgerRepository;
    }

    /** Debits spendable cash. Returns the ledger row that was appended. */
    public TransactionLedger debitCash(TradingAccount account,
                                       LedgerEntryType type,
                                       BigDecimal amount,
                                       Long orderId,
                                       Long tradeId,
                                       String description) {
        BigDecimal magnitude = amount.abs();
        account.setCashBalance(Decimals.money(account.getCashBalance().subtract(magnitude)));
        return append(account, type, magnitude.negate(), BigDecimal.ZERO, orderId, tradeId, description);
    }

    /** Credits spendable cash. */
    public TransactionLedger creditCash(TradingAccount account,
                                        LedgerEntryType type,
                                        BigDecimal amount,
                                        Long orderId,
                                        Long tradeId,
                                        String description) {
        BigDecimal magnitude = amount.abs();
        account.setCashBalance(Decimals.money(account.getCashBalance().add(magnitude)));
        return append(account, type, magnitude, BigDecimal.ZERO, orderId, tradeId, description);
    }

    /** Moves cash from spendable into the reservation. */
    public TransactionLedger reserveCash(TradingAccount account,
                                         BigDecimal amount,
                                         Long orderId,
                                         String description) {
        BigDecimal magnitude = amount.abs();
        account.setCashBalance(Decimals.money(account.getCashBalance().subtract(magnitude)));
        account.setReservedCash(Decimals.money(account.getReservedCash().add(magnitude)));
        return append(account, LedgerEntryType.CASH_RESERVED, magnitude.negate(), magnitude, orderId, null, description);
    }

    /** Moves cash out of the reservation and back into spendable cash. */
    public TransactionLedger releaseReservedCash(TradingAccount account,
                                                 BigDecimal amount,
                                                 Long orderId,
                                                 String description) {
        BigDecimal magnitude = amount.abs();
        account.setReservedCash(Decimals.money(account.getReservedCash().subtract(magnitude)));
        account.setCashBalance(Decimals.money(account.getCashBalance().add(magnitude)));
        return append(account, LedgerEntryType.CASH_RELEASED, magnitude, magnitude.negate(), orderId, null, description);
    }

    /** Absolute set of the balance, used by the reset path. */
    public TransactionLedger setBalance(TradingAccount account,
                                        LedgerEntryType type,
                                        BigDecimal targetCash,
                                        Long orderId,
                                        String description) {
        BigDecimal delta = Decimals.money(targetCash.subtract(account.getCashBalance()));
        account.setCashBalance(Decimals.money(targetCash));
        return append(account, type, delta, BigDecimal.ZERO, orderId, null, description);
    }

    /**
     * Starts a new currency-denominated virtual session. The ledger row records the new session's
     * opening cash in its own currency rather than pretending unlike currencies can be subtracted.
     */
    public TransactionLedger startNewCurrencySession(TradingAccount account, String currency,
                                                     BigDecimal openingCash, String description) {
        account.setCurrency(currency);
        account.setCashBalance(Decimals.money(openingCash));
        account.setReservedCash(BigDecimal.ZERO);
        return append(account, LedgerEntryType.ACCOUNT_RESET, openingCash, BigDecimal.ZERO,
                null, null, description);
    }

    /** Absolute set of the reservation, used by the reset path. */
    public TransactionLedger setReservation(TradingAccount account,
                                            BigDecimal targetReserved,
                                            Long orderId,
                                            String description) {
        BigDecimal delta = Decimals.money(targetReserved.subtract(account.getReservedCash()));
        account.setReservedCash(Decimals.money(targetReserved));
        return append(account, LedgerEntryType.CASH_RELEASED, BigDecimal.ZERO, delta, orderId, null, description);
    }

    /** Adjusts the lifetime realised P&L accumulator. */
    public void addRealizedPnl(TradingAccount account, BigDecimal delta) {
        account.setRealizedPnl(Decimals.money(Decimals.nullToZero(account.getRealizedPnl()).add(delta)));
    }

    /** Adjusts the lifetime charges accumulator. */
    public void addCharges(TradingAccount account, BigDecimal delta) {
        account.setTotalCharges(Decimals.money(Decimals.nullToZero(account.getTotalCharges()).add(delta)));
    }

    private TransactionLedger append(TradingAccount account,
                                     LedgerEntryType type,
                                     BigDecimal amount,
                                     BigDecimal reservedDelta,
                                     Long orderId,
                                     Long tradeId,
                                     String description) {
        TransactionLedger entry = new TransactionLedger(
                account.getId(),
                account.getCurrency(),
                type,
                Decimals.money(amount),
                Decimals.money(reservedDelta),
                Decimals.money(account.getCashBalance()),
                Decimals.money(account.getReservedCash()),
                orderId,
                tradeId,
                description);
        return ledgerRepository.save(entry);
    }
}

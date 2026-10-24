package com.tradex.websocket;

import com.tradex.dto.account.AccountDto;
import com.tradex.service.AccountChangedEvent;
import com.tradex.service.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Recomputes and pushes an account snapshot to its owner after the owning transaction commits.
 *
 * <p>{@link TransactionPhase#AFTER_COMMIT} matters: a rolled-back order must never produce a live
 * "your balance changed" push.</p>
 */
@Component
public class AccountStreamListener {

    private static final Logger log = LoggerFactory.getLogger(AccountStreamListener.class);

    private final AccountService accountService;
    private final QuoteStreamBroadcaster broadcaster;

    public AccountStreamListener(AccountService accountService, QuoteStreamBroadcaster broadcaster) {
        this.accountService = accountService;
        this.broadcaster = broadcaster;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountChanged(AccountChangedEvent event) {
        if (event.accountId() == null) {
            return;
        }
        try {
            AccountDto account = accountService.accountByAccountId(event.accountId());
            broadcaster.publishAccount(event.accountId(), account);
        } catch (RuntimeException ex) {
            log.debug("Skipped account broadcast for {}: {}", event.accountId(), ex.getMessage());
        }
    }
}

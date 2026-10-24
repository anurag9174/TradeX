package com.tradex.service;

import com.tradex.dto.account.AccountDto;

/**
 * Published inside a trading transaction. Listeners must only act <em>after commit</em>, so a
 * broadcast can never advertise a fill that was subsequently rolled back.
 */
public record AccountChangedEvent(Long accountId, String reason) {
}

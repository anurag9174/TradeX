package com.tradex.controller;

import com.tradex.dto.account.AccountDto;
import com.tradex.dto.account.AccountSummaryDto;
import com.tradex.dto.account.BalanceDto;
import com.tradex.dto.account.ResetAccountRequest;
import com.tradex.dto.account.ResetAccountResponse;
import com.tradex.security.CurrentUser;
import com.tradex.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account endpoints. Every one of them resolves the account from the authenticated principal, so a
 * client can never name somebody else's account.
 */
@RestController
@RequestMapping("/api/account")
@Tag(name = "Account", description = "Virtual account snapshot, balance and reset")
public class AccountController {

    private final AccountService accountService;
    private final CurrentUser currentUser;

    public AccountController(AccountService accountService, CurrentUser currentUser) {
        this.accountService = accountService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "Full account snapshot including the cash split and derived P&L")
    public ResponseEntity<AccountDto> account() {
        return ResponseEntity.ok(accountService.account(currentUser.requireUserId()));
    }

    @GetMapping("/balance")
    @Operation(summary = "Available cash, reserved cash and the opening balance")
    public ResponseEntity<BalanceDto> balance() {
        return ResponseEntity.ok(accountService.balance(currentUser.requireUserId()));
    }

    @GetMapping("/summary")
    @Operation(summary = "The dashboard summary cards")
    public ResponseEntity<AccountSummaryDto> summary() {
        return ResponseEntity.ok(accountService.summary(currentUser.requireUserId()));
    }

    @PostMapping("/reset")
    @Operation(summary = "Restart the simulated account with the initial virtual balance. Requires confirm=true")
    public ResponseEntity<ResetAccountResponse> reset(@Valid @RequestBody(required = false) ResetAccountRequest request) {
        return ResponseEntity.ok(accountService.reset(currentUser.requireUserId(),
                request == null ? new ResetAccountRequest(false, null, null, null) : request));
    }
}

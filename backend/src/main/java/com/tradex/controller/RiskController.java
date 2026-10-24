package com.tradex.controller;

import com.tradex.dto.account.AccountDto;
import com.tradex.dto.common.PageResponse;
import com.tradex.dto.risk.CreateJournalEntryRequest;
import com.tradex.dto.risk.JournalEntryDto;
import com.tradex.dto.risk.OpenPositionRisk;
import com.tradex.dto.risk.PositionSizeRequest;
import com.tradex.dto.risk.PositionSizeResponse;
import com.tradex.dto.risk.RiskSettingsRequest;
import com.tradex.dto.risk.RiskSummaryDto;
import com.tradex.security.CurrentUser;
import com.tradex.service.AccountService;
import com.tradex.service.RiskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Risk console: exposure, the daily-loss guard, the position-size calculator and the trade journal.
 *
 * <p>Every number here is derived from the account's own simulated data. The payload always carries
 * the project disclaimer so clients never present it as advice.</p>
 */
@RestController
@RequestMapping("/api/risk")
@Tag(name = "Risk", description = "Risk summary, position sizing, journal and account risk settings")
public class RiskController {

    private final RiskService riskService;
    private final AccountService accountService;
    private final CurrentUser currentUser;

    public RiskController(RiskService riskService, AccountService accountService, CurrentUser currentUser) {
        this.riskService = riskService;
        this.accountService = accountService;
        this.currentUser = currentUser;
    }

    @GetMapping("/summary")
    @Operation(summary = "Daily P&L, exposure, open-position risk, guard status and health metrics")
    public ResponseEntity<RiskSummaryDto> summary() {
        return ResponseEntity.ok(riskService.summary(currentUser.requireUserId()));
    }

    @GetMapping("/open-positions")
    @Operation(summary = "Open positions with their risk per unit, capital at risk and planned reward")
    public ResponseEntity<List<OpenPositionRisk>> openPositions() {
        return ResponseEntity.ok(riskService.summary(currentUser.requireUserId()).openPositions());
    }

    @PostMapping("/position-size")
    @Operation(summary = "Position-size calculator: risk-based quantity, reward:risk and break-even")
    public ResponseEntity<PositionSizeResponse> positionSize(@Valid @RequestBody PositionSizeRequest request) {
        return ResponseEntity.ok(riskService.positionSize(currentUser.requireUserId(), request));
    }

    @GetMapping("/journal")
    @Operation(summary = "Paged trade journal, newest first")
    public ResponseEntity<PageResponse<JournalEntryDto>> journal(
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "size", required = false, defaultValue = "20") int size) {
        return ResponseEntity.ok(PageResponse.of(riskService.journal(currentUser.requireUserId(), page, size)));
    }

    @PostMapping("/journal")
    @Operation(summary = "Add a journal note, optionally linked to a symbol, order and risk/reward plan")
    public ResponseEntity<JournalEntryDto> addJournal(@Valid @RequestBody CreateJournalEntryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(riskService.addJournal(currentUser.requireUserId(), request));
    }

    @DeleteMapping("/journal/{id}")
    @Operation(summary = "Delete one of your own journal entries")
    public ResponseEntity<Void> deleteJournal(@PathVariable("id") Long id) {
        riskService.deleteJournal(currentUser.requireUserId(), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/settings")
    @Operation(summary = "Configure the daily-loss limit and trading block; clearBreach resumes trading")
    public ResponseEntity<AccountDto> settings(@Valid @RequestBody RiskSettingsRequest request) {
        Long userId = currentUser.requireUserId();
        accountService.updateRiskSettings(userId, request.dailyLossLimit(),
                request.dailyLossLimitEnabled(), request.tradingBlocked(), request.shouldClearBreach());
        return ResponseEntity.ok(accountService.account(userId));
    }
}

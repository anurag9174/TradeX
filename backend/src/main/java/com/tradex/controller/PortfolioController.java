package com.tradex.controller;

import com.tradex.dto.portfolio.HoldingDto;
import com.tradex.dto.portfolio.PerformanceDto;
import com.tradex.dto.portfolio.PortfolioDto;
import com.tradex.entity.TradingAccount;
import com.tradex.exception.BadRequestException;
import com.tradex.security.CurrentUser;
import com.tradex.service.AccountService;
import com.tradex.trading.PortfolioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Portfolio reads. Holdings are valued at the live quote on every request, and the average cost of
 * each position includes the buy-side charges that produced it.
 */
@RestController
@RequestMapping("/api/portfolio")
@Tag(name = "Portfolio", description = "Holdings, valuation and the equity curve")
public class PortfolioController {

    private final PortfolioService portfolioService;
    private final AccountService accountService;
    private final CurrentUser currentUser;

    public PortfolioController(PortfolioService portfolioService,
                               AccountService accountService,
                               CurrentUser currentUser) {
        this.portfolioService = portfolioService;
        this.accountService = accountService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "Valued holdings with market value, unrealized P&L and allocation")
    public ResponseEntity<PortfolioDto> portfolio() {
        return ResponseEntity.ok(portfolioService.portfolio(account()));
    }

    @GetMapping("/holdings")
    @Operation(summary = "Just the open positions, valued at the latest quote")
    public ResponseEntity<List<HoldingDto>> holdings() {
        return ResponseEntity.ok(portfolioService.holdings(account()));
    }

    @GetMapping("/performance")
    @Operation(summary = "Realised equity curve and trade statistics between two dates (default: last 30 days)")
    public ResponseEntity<PerformanceDto> performance(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) {
        return ResponseEntity.ok(portfolioService.performance(account(), parseDate("from", from), parseDate("to", to)));
    }

    private TradingAccount account() {
        return accountService.requireAccount(currentUser.requireUserId());
    }

    private static LocalDate parseDate(String name, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new BadRequestException("INVALID_DATE",
                    "'" + name + "' must be an ISO date such as 2026-01-31.");
        }
    }
}

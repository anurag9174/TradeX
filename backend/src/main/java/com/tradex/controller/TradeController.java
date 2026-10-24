package com.tradex.controller;

import com.tradex.dto.common.PageResponse;
import com.tradex.dto.trade.TradeDto;
import com.tradex.enums.OrderSide;
import com.tradex.exception.BadRequestException;
import com.tradex.security.CurrentUser;
import com.tradex.service.TradeService;
import com.tradex.trading.TradingClock;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Executed-trade history and CSV export. Trades are immutable; this API can only read them.
 */
@RestController
@RequestMapping("/api/trades")
@Tag(name = "Trades", description = "Executed trade history with filters and CSV export")
public class TradeController {

    private final TradeService tradeService;
    private final CurrentUser currentUser;
    private final TradingClock clock;

    public TradeController(TradeService tradeService, CurrentUser currentUser, TradingClock clock) {
        this.tradeService = tradeService;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "Paged trade history. Filters: symbol, side, from, to, result (all|profit|loss)")
    public ResponseEntity<PageResponse<TradeDto>> list(
            @RequestParam(name = "symbol", required = false) String symbol,
            @RequestParam(name = "side", required = false) String side,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "result", required = false) String result,
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "size", required = false, defaultValue = "25") int size) {
        return ResponseEntity.ok(tradeService.list(currentUser.requireUserId(), symbol, parseSide(side),
                parseDate("from", from), parseDate("to", to), result, page, size));
    }

    @GetMapping("/export")
    @Operation(summary = "RFC-4180 CSV of every trade matching the same filters, newest first")
    public ResponseEntity<byte[]> export(
            @RequestParam(name = "symbol", required = false) String symbol,
            @RequestParam(name = "side", required = false) String side,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "result", required = false) String result) {
        String csv = tradeService.exportCsv(currentUser.requireUserId(), symbol, parseSide(side),
                parseDate("from", from), parseDate("to", to), result);
        String filename = "tradex-trades-" + clock.today() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One trade by id. Only the owner's trades are visible")
    public ResponseEntity<TradeDto> get(@PathVariable("id") Long id) {
        return ResponseEntity.ok(tradeService.get(currentUser.requireUserId(), id));
    }

    @GetMapping("/by-ref/{tradeRef}")
    @Operation(summary = "One trade by its human-readable reference, e.g. TRD-20260131-000042")
    public ResponseEntity<TradeDto> byRef(@PathVariable("tradeRef") String tradeRef) {
        return ResponseEntity.ok(tradeService.getByRef(currentUser.requireUserId(), tradeRef));
    }

    private static OrderSide parseSide(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OrderSide.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("INVALID_SIDE", "Side must be BUY or SELL.");
        }
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

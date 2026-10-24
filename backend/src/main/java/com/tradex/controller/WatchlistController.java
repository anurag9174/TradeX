package com.tradex.controller;

import com.tradex.dto.market.InstrumentDto;
import com.tradex.dto.market.WatchlistDto;
import com.tradex.security.CurrentUser;
import com.tradex.service.WatchlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Per-account watchlist. The default set comes from the {@code watchlist_template} table that the
 * V2 migration ships, so new accounts start with the NIFTY50 / SENSEX / large-cap / crypto mix
 * without any code knowing that list.
 */
@RestController
@RequestMapping("/api/watchlist")
@Tag(name = "Watchlist", description = "The user's own watchlist and the instrument catalogue")
public class WatchlistController {

    private final WatchlistService watchlistService;
    private final CurrentUser currentUser;

    public WatchlistController(WatchlistService watchlistService, CurrentUser currentUser) {
        this.watchlistService = watchlistService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "The watchlist with a live quote per instrument and the active data source")
    public ResponseEntity<WatchlistDto> list() {
        return ResponseEntity.ok(watchlistService.list(currentUser.requireUserId()));
    }

    @PostMapping
    @Operation(summary = "Add an instrument to the watchlist")
    public ResponseEntity<WatchlistDto.Item> add(@Valid @RequestBody AddSymbolRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(watchlistService.add(currentUser.requireUserId(), request.symbol()));
    }

    @DeleteMapping("/{symbol}")
    @Operation(summary = "Remove an instrument from the watchlist")
    public ResponseEntity<Void> remove(@PathVariable("symbol") String symbol) {
        watchlistService.remove(currentUser.requireUserId(), symbol);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/catalogue")
    @Operation(summary = "Every instrument that can be added, annotated with tradeability and provider support")
    public ResponseEntity<List<InstrumentDto>> catalogue() {
        return ResponseEntity.ok(watchlistService.catalogue());
    }

    public record AddSymbolRequest(
            @NotBlank(message = "Symbol is required")
            @Size(max = 40)
            String symbol) {
    }
}

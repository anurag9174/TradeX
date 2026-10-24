package com.tradex.controller;

import com.tradex.dto.common.PageResponse;
import com.tradex.dto.order.OrderActionResponse;
import com.tradex.dto.order.OrderDto;
import com.tradex.dto.order.OrderEstimateDto;
import com.tradex.dto.order.OrderEstimateRequest;
import com.tradex.dto.order.PlaceOrderRequest;
import com.tradex.enums.OrderSide;
import com.tradex.exception.BadRequestException;
import com.tradex.security.CurrentUser;
import com.tradex.trading.OrderService;
import com.tradex.trading.TradingClock;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Order intake and lifecycle.
 *
 * <p>The client only ever names the instrument, side, type and quantity. Prices, balances, charges
 * and P&amp;L are computed server-side from the authoritative quote, so a tampered request cannot
 * influence a financial number. Replays of the same {@code Idempotency-Key} return the original
 * order instead of placing a second one.</p>
 */
@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Place, estimate, list, inspect and cancel simulated orders")
public class OrderController {

    private final OrderService orderService;
    private final CurrentUser currentUser;
    private final TradingClock clock;

    public OrderController(OrderService orderService, CurrentUser currentUser, TradingClock clock) {
        this.orderService = orderService;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @PostMapping
    @Operation(summary = "Place a market, limit or stop-loss order. Duplicate keys are replayed safely")
    public ResponseEntity<OrderActionResponse> place(
            @Valid @RequestBody PlaceOrderRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        PlaceOrderRequest effective = withHeaderKey(request, idempotencyKey);
        OrderActionResponse response = orderService.placeOrder(currentUser.requireUserId(), effective);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/estimate")
    @Operation(summary = "Pre-trade preview: value, charges, affordability and risk. Places nothing")
    public ResponseEntity<OrderEstimateDto> estimate(@Valid @RequestBody OrderEstimateRequest request) {
        return ResponseEntity.ok(orderService.estimate(currentUser.requireUserId(), request));
    }

    @GetMapping
    @Operation(summary = "Paged order history. Tabs: all, open, executed, cancelled, rejected")
    public ResponseEntity<PageResponse<OrderDto>> list(
            @RequestParam(name = "tab", required = false, defaultValue = "all") String tab,
            @RequestParam(name = "symbol", required = false) String symbol,
            @RequestParam(name = "side", required = false) String side,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "size", required = false, defaultValue = "25") int size,
            @RequestParam(name = "sort", required = false) String sort) {
        return ResponseEntity.ok(orderService.listOrders(currentUser.requireUserId(), tab, symbol,
                parseSide(side), parseFrom(from), parseTo(to), page, size, sort));
    }

    @GetMapping("/open")
    @Operation(summary = "Every working order (pending or partially filled), newest first")
    public ResponseEntity<List<OrderDto>> open() {
        return ResponseEntity.ok(orderService.openOrders(currentUser.requireUserId()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One order by id. Only the owner's orders are visible")
    public ResponseEntity<OrderDto> get(@PathVariable("id") Long id) {
        return ResponseEntity.ok(orderService.getOrder(currentUser.requireUserId(), id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Cancel a working order and release any cash it had reserved")
    public ResponseEntity<OrderDto> cancel(@PathVariable("id") Long id) {
        return ResponseEntity.ok(orderService.cancelOrder(currentUser.requireUserId(), id));
    }

    // ---------------------------------------------------------------------------------------

    /** Body key wins; the header is only a fallback so CORS clients keep the same guarantee. */
    private static PlaceOrderRequest withHeaderKey(PlaceOrderRequest request, String headerKey) {
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            return request;
        }
        if (headerKey == null || headerKey.isBlank()) {
            return request;
        }
        return new PlaceOrderRequest(request.symbol(), request.side(), request.orderType(),
                request.quantity(), request.limitPrice(), request.stopPrice(),
                request.positionStopLoss(), request.positionTarget(), headerKey.trim());
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

    /** ISO-8601 instant, or a plain date interpreted as the first moment of that day. */
    private Instant parseFrom(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // fall through to a date
        }
        try {
            return clock.startOfDay(LocalDate.parse(value));
        } catch (DateTimeParseException ex) {
            throw new BadRequestException("INVALID_TIMESTAMP",
                    "'from' must be an ISO-8601 instant (2026-01-31T09:15:00Z) or date (2026-01-31).");
        }
    }

    /** ISO-8601 instant, or a plain date interpreted as the last moment of that day. */
    private Instant parseTo(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // fall through to a date
        }
        try {
            return clock.startOfDay(LocalDate.parse(value), 1).minusNanos(1);
        } catch (DateTimeParseException ex) {
            throw new BadRequestException("INVALID_TIMESTAMP",
                    "'to' must be an ISO-8601 instant (2026-01-31T15:30:00Z) or date (2026-01-31).");
        }
    }
}

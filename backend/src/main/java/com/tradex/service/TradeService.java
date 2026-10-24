package com.tradex.service;

import com.tradex.dto.common.PageResponse;
import com.tradex.dto.trade.TradeDto;
import com.tradex.entity.Instrument;
import com.tradex.entity.Order;
import com.tradex.entity.Trade;
import com.tradex.entity.TradingAccount;
import com.tradex.enums.OrderSide;
import com.tradex.exception.BadRequestException;
import com.tradex.exception.ResourceNotFoundException;
import com.tradex.repository.OrderRepository;
import com.tradex.repository.TradeRepository;
import com.tradex.repository.TradingAccountRepository;
import com.tradex.trading.CurrencyConverter;
import com.tradex.trading.Decimals;
import com.tradex.trading.TradingClock;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Trade history, filtering and CSV export. */
@Service
public class TradeService {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final TradingAccountRepository accountRepository;
    private final InstrumentService instrumentService;
    private final TradingClock clock;
    private final CurrencyConverter currencyConverter;

    public TradeService(TradeRepository tradeRepository,
                        OrderRepository orderRepository,
                        TradingAccountRepository accountRepository,
                        InstrumentService instrumentService,
                        TradingClock clock,
                        CurrencyConverter currencyConverter) {
        this.tradeRepository = tradeRepository;
        this.orderRepository = orderRepository;
        this.accountRepository = accountRepository;
        this.instrumentService = instrumentService;
        this.clock = clock;
        this.currencyConverter = currencyConverter;
    }

    public record TradeFilter(Long instrumentId, OrderSide side, Instant from, Instant to, Boolean profitable) {
    }

    @Transactional(readOnly = true)
    public PageResponse<TradeDto> list(Long userId, String symbol, OrderSide side, LocalDate from, LocalDate to,
                                       String result, int page, int size) {
        TradingAccount account = requireAccount(userId);
        TradeFilter filter = resolve(symbol, side, from, to, result);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 500),
                Sort.by(Sort.Direction.DESC, "executedAt"));
        Page<Trade> trades = tradeRepository.search(account.getId(), filter.instrumentId(), filter.side(),
                filter.from(), filter.to(), pageable);
        Map<Long, String> orderRefs = orderRefs(trades.getContent());
        List<TradeDto> dtos = trades.getContent().stream()
                .map(trade -> toDto(trade, orderRefs.get(trade.getOrderId()), account.getCurrency()))
                .toList();
        if (filter.profitable() != null) {
            dtos = dtos.stream()
                    .filter(dto -> filter.profitable()
                            ? Decimals.nullToZero(dto.realizedPnl()).signum() > 0
                            : Decimals.nullToZero(dto.realizedPnl()).signum() < 0)
                    .toList();
            return new PageResponse<>(dtos, trades.getNumber(), trades.getSize(), dtos.size(), 1, true, true);
        }
        return new PageResponse<>(dtos, trades.getNumber(), trades.getSize(), trades.getTotalElements(),
                trades.getTotalPages(), trades.isFirst(), trades.isLast());
    }

    @Transactional(readOnly = true)
    public TradeDto get(Long userId, Long tradeId) {
        TradingAccount account = requireAccount(userId);
        Trade trade = tradeRepository.findById(tradeId)
                .filter(candidate -> candidate.getAccountId().equals(account.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("TRADE_NOT_FOUND",
                        "Trade " + tradeId + " was not found."));
        return toDto(trade, orderRepository.findById(trade.getOrderId())
                .map(Order::getOrderRef).orElse(null), account.getCurrency());
    }

    @Transactional(readOnly = true)
    public TradeDto getByRef(Long userId, String tradeRef) {
        TradingAccount account = requireAccount(userId);
        Trade trade = tradeRepository.findByTradeRefAndAccountId(tradeRef, account.getId())
                .orElseThrow(() -> new ResourceNotFoundException("TRADE_NOT_FOUND",
                        "Trade " + tradeRef + " was not found."));
        return toDto(trade, orderRepository.findById(trade.getOrderId())
                .map(Order::getOrderRef).orElse(null), account.getCurrency());
    }

    /** RFC-4180 CSV of every trade matching the filter, newest first. */
    @Transactional(readOnly = true)
    public String exportCsv(Long userId, String symbol, OrderSide side, LocalDate from, LocalDate to, String result) {
        TradingAccount account = requireAccount(userId);
        TradeFilter filter = resolve(symbol, side, from, to, result);
        List<Trade> trades = tradeRepository.searchAll(account.getId(), filter.instrumentId(), filter.side(),
                filter.from(), filter.to());
        Map<Long, String> orderRefs = orderRefs(trades);

        StringWriter writer = new StringWriter();
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader("Trade ID", "Order Ref", "Symbol", "Instrument", "Exchange", "Side", "Quantity",
                        "Price", "Price Currency", "Gross Amount", "Charges", "Net Amount", "Cost Basis",
                        "Realized PnL", "Realized PnL %", "Account Currency",
                        "Executed At (" + clock.zoneId() + ")")
                .build();
        try (CSVPrinter printer = new CSVPrinter(writer, format)) {
            for (Trade trade : trades) {
                Instrument instrument = instrumentService.find(trade.getInstrumentId()).orElse(null);
                BigDecimal basisLedger = currencyConverter.toLedger(instrument,
                        Decimals.nullToZero(trade.getCostBasis()).multiply(trade.getQuantity(), Decimals.MC),
                        account.getCurrency());
                BigDecimal realizedPct = basisLedger.signum() == 0
                        ? BigDecimal.ZERO
                        : Decimals.percentChange(basisLedger,
                        basisLedger.add(Decimals.nullToZero(trade.getRealizedPnl())));
                printer.printRecord(
                        trade.getTradeRef(),
                        orderRefs.getOrDefault(trade.getOrderId(), ""),
                        instrument == null ? "?" : instrument.getSymbol(),
                        instrument == null ? "" : instrument.getName(),
                        instrument == null ? "" : instrument.getExchange(),
                        trade.getSide().name(),
                        plain(trade.getQuantity()),
                        plain(trade.getPrice()),
                        instrument == null ? "INR" : instrument.getCurrency(),
                        plain(trade.getGrossAmount()),
                        plain(trade.getCharges()),
                        plain(trade.getNetAmount()),
                        trade.getCostBasis() == null ? "" : plain(trade.getCostBasis()),
                        plain(trade.getRealizedPnl()),
                        plain(realizedPct),
                        account.getCurrency(),
                        local(trade.getExecutedAt()));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to build the CSV export", ex);
        }
        return writer.toString();
    }

    // ---------------------------------------------------------------------------------------

    private TradeFilter resolve(String symbol, OrderSide side, LocalDate from, LocalDate to, String result) {
        Long instrumentId = null;
        if (symbol != null && !symbol.isBlank()) {
            instrumentId = instrumentService.require(symbol).getId();
        }
        Instant fromInstant = from == null ? null : clock.startOfDay(from);
        Instant toInstant = to == null ? null : clock.startOfDay(to, 1).minusNanos(1);
        if (fromInstant != null && toInstant != null && toInstant.isBefore(fromInstant)) {
            throw new BadRequestException("INVALID_DATE_RANGE", "The 'to' date must not precede the 'from' date.");
        }
        Boolean profitable = null;
        if (result != null && !result.isBlank()) {
            String normalized = result.trim().toLowerCase();
            switch (normalized) {
                case "profit", "profitable", "win", "wins" -> profitable = true;
                case "loss", "losses", "losing" -> profitable = false;
                case "all" -> profitable = null;
                default -> throw new BadRequestException("INVALID_RESULT_FILTER",
                        "Result filter must be one of: all, profit, loss.");
            }
        }
        return new TradeFilter(instrumentId, side, fromInstant, toInstant, profitable);
    }

    private Map<Long, String> orderRefs(List<Trade> trades) {
        Map<Long, String> map = new HashMap<>(trades.size() * 2);
        List<Long> ids = new ArrayList<>();
        for (Trade trade : trades) {
            ids.add(trade.getOrderId());
        }
        if (!ids.isEmpty()) {
            for (Order order : orderRepository.findAllById(ids)) {
                map.put(order.getId(), order.getOrderRef());
            }
        }
        return map;
    }

    public TradeDto toDto(Trade trade, String orderRef) {
        TradingAccount account = requireAccountById(trade.getAccountId());
        return toDto(trade, orderRef, account.getCurrency());
    }

    private TradeDto toDto(Trade trade, String orderRef, String accountCurrency) {
        Instrument instrument = instrumentService.find(trade.getInstrumentId()).orElse(null);
        BigDecimal realizedPct = null;
        if (trade.getSide() == OrderSide.SELL) {
            // The cost basis is a price-space amount; realised P&L is ledger money. Convert the
            // basis before taking the ratio so the percentage is dimensionally honest.
            BigDecimal basis = currencyConverter.toLedger(instrument,
                    Decimals.nullToZero(trade.getCostBasis()).multiply(trade.getQuantity(), Decimals.MC),
                    accountCurrency);
            realizedPct = Decimals.percentChange(basis, basis.add(Decimals.nullToZero(trade.getRealizedPnl())));
        }
        return new TradeDto(
                trade.getId(),
                trade.getTradeRef(),
                orderRef,
                instrument == null ? "?" : instrument.getSymbol(),
                instrument == null ? "Unknown instrument" : instrument.getName(),
                instrument == null ? null : instrument.getExchange(),
                instrument == null ? null : instrument.getCurrency(),
                accountCurrency,
                trade.getSide(),
                trade.getQuantity(),
                trade.getPrice(),
                trade.getGrossAmount(),
                trade.getCharges(),
                trade.getNetAmount(),
                trade.getCostBasis(),
                trade.getRealizedPnl(),
                realizedPct,
                trade.getExecutedAt());
    }

    private TradingAccount requireAccount(Long userId) {
        return accountRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("ACCOUNT_NOT_FOUND",
                        "No trading account exists for this user."));
    }

    private TradingAccount requireAccountById(Long accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("ACCOUNT_NOT_FOUND",
                        "No trading account exists for this trade."));
    }

    private String local(Instant instant) {
        ZonedDateTime zoned = instant.atZone(clock.zone());
        return TIMESTAMP.format(zoned);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }
}

package com.tradex.dto.market;

import com.tradex.enums.MarketDataSource;

import java.time.Instant;
import java.util.List;

public record WatchlistDto(
        List<Item> items,
        MarketDataSource dataSource,
        String dataSourceLabel,
        Instant asOf) {

    public record Item(
            Long instrumentId,
            String symbol,
            String name,
            String exchange,
            String assetClass,
            int sortOrder,
            QuoteDto quote) {
    }
}

package com.tradex.dto.market;

import com.tradex.enums.MarketDataSource;
import com.tradex.enums.MarketStatus;

import java.time.Instant;
import java.util.Map;

public record MarketStatusDto(
        MarketStatus status,
        String statusLabel,
        /** {@code SIMULATED} or {@code LIVE} - what the prices actually are, not which venue serves them. */
        MarketDataSource dataSource,
        String dataSourceLabel,
        String provider,
        boolean simulated,
        Instant serverTime,
        Map<String, String> sessions) {
}

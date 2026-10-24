package com.tradex.dto.market;

import com.tradex.enums.AssetClass;
import com.tradex.enums.MarketDataSource;

import java.math.BigDecimal;

public record InstrumentDto(
        Long id,
        String symbol,
        String name,
        String exchange,
        AssetClass assetClass,
        String currency,
        BigDecimal tickSize,
        BigDecimal lotSize,
        boolean tradable,
        boolean active,
        boolean providerSupported,
        String sector,

        /** True when the platform has to synthesise prices for this instrument. */
        boolean simulated,
        MarketDataSource dataSource,
        String dataSourceNote) {
}

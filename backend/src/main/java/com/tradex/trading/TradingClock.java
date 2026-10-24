package com.tradex.trading;

import com.tradex.config.TradingProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Single source of truth for "what day is it".
 *
 * <p>Daily P&amp;L, the daily-loss guard and the performance series all key off this clock, using a
 * configurable trading zone (default {@code Asia/Kolkata}). Using one component everywhere is what
 * keeps "today's P&amp;L" in the summary card identical to the number the risk console enforces.</p>
 */
@Component
public class TradingClock {

    private final ZoneId zone;
    private final Clock clock;

    public TradingClock(TradingProperties properties) {
        this.zone = resolveZone(properties.getTradingZone());
        this.clock = Clock.system(zone);
    }

    private static ZoneId resolveZone(String configured) {
        try {
            return configured == null || configured.isBlank() ? ZoneId.of("Asia/Kolkata") : ZoneId.of(configured);
        } catch (Exception ex) {
            return ZoneId.of("Asia/Kolkata");
        }
    }

    public ZoneId zone() {
        return zone;
    }

    public String zoneId() {
        return zone.getId();
    }

    public Clock clock() {
        return clock;
    }

    public Instant now() {
        return Instant.now();
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** First instant of the given date in the trading zone. */
    public Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(zone).toInstant();
    }

    /** First instant of the given date shifted by {@code days}. */
    public Instant startOfDay(LocalDate date, int plusDays) {
        return startOfDay(date.plusDays(plusDays));
    }

    public Instant startOfToday() {
        return startOfDay(today());
    }

    public Instant startOfTomorrow() {
        return startOfDay(today(), 1);
    }

    public LocalDate dateOf(Instant instant) {
        return instant.atZone(zone).toLocalDate();
    }
}

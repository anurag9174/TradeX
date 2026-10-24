package com.tradex.enums;

/** Supported chart timeframes. */
public enum Timeframe {
    M1("1m", 60L),
    M5("5m", 300L),
    M15("15m", 900L),
    M30("30m", 1800L),
    H1("1h", 3600L),
    H4("4h", 14400L),
    D1("1d", 86400L),
    W1("1w", 604800L);

    private final String code;
    private final long seconds;

    Timeframe(String code, long seconds) {
        this.code = code;
        this.seconds = seconds;
    }

    public String code() {
        return code;
    }

    /** Nominal length of one candle in seconds. */
    public long seconds() {
        return seconds;
    }

    public static Timeframe fromCode(String code) {
        if (code == null || code.isBlank()) {
            return M1;
        }
        String normalized = code.trim().toLowerCase();
        for (Timeframe tf : values()) {
            if (tf.code.equals(normalized) || tf.name().equalsIgnoreCase(normalized)) {
                return tf;
            }
        }
        throw new IllegalArgumentException("Unsupported timeframe: " + code);
    }
}

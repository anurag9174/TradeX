package com.tradex.trading;

import java.security.SecureRandom;

/** Generates short, unambiguous, non-sequential public references for orders and trades. */
public final class Refs {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private Refs() {
    }

    public static String order() {
        return "ORD-" + token(10);
    }

    public static String trade() {
        return "TRD-" + token(10);
    }

    public static String account(String username) {
        String base = username == null ? "ACC" : username.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (base.length() > 8) {
            base = base.substring(0, 8);
        }
        if (base.isEmpty()) {
            base = "ACC";
        }
        return base + "-" + token(6);
    }

    private static String token(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(HEX[RANDOM.nextInt(HEX.length)]);
        }
        return builder.toString();
    }
}

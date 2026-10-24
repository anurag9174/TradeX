package com.tradex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Security-related knobs. The JWT secret MUST come from the environment in any real deployment. */
@ConfigurationProperties(prefix = "tradex.security")
public class SecurityProperties {

    /** Base64 or plain text secret. Minimum 32 bytes of entropy is enforced at startup. */
    private String jwtSecret = "tradex-development-only-secret-change-me-please-0123456789";

    /** Access-token lifetime in minutes. */
    private long jwtExpiryMinutes = 720;

    private String issuer = "tradex";

    /** Comma-separated list of origins allowed by CORS. */
    private String allowedOrigins = "http://localhost:5173,http://127.0.0.1:5173";

    private final RateLimit rateLimit = new RateLimit();

    /** When true, the seeded demo user is created on startup (development convenience). */
    private boolean seedDemoUser = false;

    private String demoUsername = "demo";
    private String demoPassword = "demo12345";
    private String demoEmail = "demo@tradex.local";

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public long getJwtExpiryMinutes() {
        return jwtExpiryMinutes;
    }

    public void setJwtExpiryMinutes(long jwtExpiryMinutes) {
        this.jwtExpiryMinutes = jwtExpiryMinutes;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public boolean isSeedDemoUser() {
        return seedDemoUser;
    }

    public void setSeedDemoUser(boolean seedDemoUser) {
        this.seedDemoUser = seedDemoUser;
    }

    public String getDemoUsername() {
        return demoUsername;
    }

    public void setDemoUsername(String demoUsername) {
        this.demoUsername = demoUsername;
    }

    public String getDemoPassword() {
        return demoPassword;
    }

    public void setDemoPassword(String demoPassword) {
        this.demoPassword = demoPassword;
    }

    public String getDemoEmail() {
        return demoEmail;
    }

    public void setDemoEmail(String demoEmail) {
        this.demoEmail = demoEmail;
    }

    /** Simple fixed-window rate limiter applied to sensitive endpoints. */
    public static class RateLimit {

        private boolean enabled = true;

        /** Maximum requests allowed per window, per client IP. */
        private int capacity = 30;

        /** Window length in seconds. */
        private int windowSeconds = 60;

        /** Separate, tighter limit for authentication endpoints. */
        private int authCapacity = 12;

        private int authWindowSeconds = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public int getAuthCapacity() {
            return authCapacity;
        }

        public void setAuthCapacity(int authCapacity) {
            this.authCapacity = authCapacity;
        }

        public int getAuthWindowSeconds() {
            return authWindowSeconds;
        }

        public void setAuthWindowSeconds(int authWindowSeconds) {
            this.authWindowSeconds = authWindowSeconds;
        }
    }
}

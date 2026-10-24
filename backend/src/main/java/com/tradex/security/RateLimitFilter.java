package com.tradex.security;

import com.tradex.config.SecurityProperties;
import com.tradex.exception.RateLimitExceededException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window in-memory rate limiter for sensitive endpoints.
 *
 * <p>Keyed by client IP and bucket. Authentication endpoints get a tighter budget than trading
 * endpoints. A single-node deployment is the supported topology for TradeX, so an in-memory counter
 * is sufficient and avoids an external dependency; the class is a drop-in seam for Redis if the app
 * is ever scaled out.</p>
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final SecurityProperties properties;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(SecurityProperties properties) {
        this.properties = properties;
    }

    private static final class Window {
        private final long startMillis;
        private final AtomicInteger count = new AtomicInteger();

        private Window(long startMillis) {
            this.startMillis = startMillis;
        }
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !properties.getRateLimit().isEnabled() || bucketFor(request) == null;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        SecurityProperties.RateLimit config = properties.getRateLimit();
        String bucket = bucketFor(request);
        boolean auth = "auth".equals(bucket);
        int capacity = auth ? config.getAuthCapacity() : config.getCapacity();
        int windowSeconds = auth ? config.getAuthWindowSeconds() : config.getWindowSeconds();

        String key = bucket + "|" + clientIp(request);
        long now = System.currentTimeMillis();
        Window window = windows.compute(key, (k, existing) -> {
            if (existing == null || now - existing.startMillis >= windowSeconds * 1000L) {
                return new Window(now);
            }
            return existing;
        });
        int used = window.count.incrementAndGet();
        if (used > capacity) {
            long retryAfterSeconds = Math.max(1, windowSeconds - (now - window.startMillis) / 1000);
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            throw new RateLimitExceededException(
                    "Too many requests. Limit is " + capacity + " per " + windowSeconds
                            + "s for this endpoint. Retry in " + retryAfterSeconds + "s.");
        }
        if (windows.size() > 10_000) {
            pruneWindows(now, windowSeconds * 1000L);
        }
        filterChain.doFilter(request, response);
    }

    private void pruneWindows(long now, long windowMillis) {
        windows.entrySet().removeIf(entry -> now - entry.getValue().startMillis > windowMillis * 4);
    }

    private static String bucketFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return null;
        }
        if (path.startsWith("/api/auth/")) {
            return "auth";
        }
        if (path.startsWith("/api/orders") && "POST".equalsIgnoreCase(request.getMethod())) {
            return "orders";
        }
        if (path.startsWith("/api/account/reset")) {
            return "reset";
        }
        return null;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}

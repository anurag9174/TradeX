package com.tradex.dto.auth;

import java.time.Instant;

public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        Instant issuedAt,
        Instant expiresAt,
        UserProfileDto user) {
}

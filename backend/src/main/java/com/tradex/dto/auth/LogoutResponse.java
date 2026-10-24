package com.tradex.dto.auth;

/** Body of {@code POST /api/auth/logout}. Stateless JWTs are discarded client-side. */
public record LogoutResponse(boolean loggedOut, String message) {
}

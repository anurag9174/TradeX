package com.tradex.security;

import com.tradex.exception.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Reads the authenticated principal out of the security context. */
@Component
public class CurrentUser {

    /** @return the principal, or {@code null} when the request is anonymous. */
    public UserPrincipal principalOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof UserPrincipal userPrincipal ? userPrincipal : null;
    }

    public UserPrincipal require() {
        UserPrincipal principal = principalOrNull();
        if (principal == null) {
            throw new UnauthorizedException("Authentication is required for this operation.");
        }
        return principal;
    }

    public Long requireUserId() {
        return require().getUserId();
    }

    /** Admins may inspect any account; this is the only place that bypasses user scoping. */
    public boolean isAdmin() {
        UserPrincipal principal = principalOrNull();
        return principal != null && principal.getRole() == com.tradex.enums.Role.ADMIN;
    }
}

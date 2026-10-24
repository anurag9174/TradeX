package com.tradex.dto.auth;

import com.tradex.enums.Role;

import java.time.Instant;

public record UserProfileDto(
        Long id,
        String email,
        String username,
        String fullName,
        Role role,
        Instant createdAt) {
}

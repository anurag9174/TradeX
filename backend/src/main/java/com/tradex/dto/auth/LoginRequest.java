package com.tradex.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "Email or username is required")
        @Size(max = 190)
        String identifier,

        @NotBlank(message = "Password is required")
        @Size(max = 72)
        String password) {
}

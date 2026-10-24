package com.tradex.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record RegisterRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        @Size(max = 190, message = "Email must be at most 190 characters")
        String email,

        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 60, message = "Username must be between 3 and 60 characters")
        @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                message = "Username may only contain letters, digits, dot, underscore and hyphen")
        String username,

        @Size(max = 120, message = "Full name must be at most 120 characters")
        String fullName,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String password,

        @Digits(integer = 7, fraction = 2, message = "Virtual capital must have at most 2 decimal places")
        BigDecimal initialCapital) {
}

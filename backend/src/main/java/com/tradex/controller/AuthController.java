package com.tradex.controller;

import com.tradex.dto.auth.AuthResponse;
import com.tradex.dto.auth.LoginRequest;
import com.tradex.dto.auth.LogoutResponse;
import com.tradex.dto.auth.RegisterRequest;
import com.tradex.dto.auth.UserProfileDto;
import com.tradex.security.CurrentUser;
import com.tradex.security.UserPrincipal;
import com.tradex.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Registration, login and the current profile")
public class AuthController {

    private final AuthService authService;
    private final CurrentUser currentUser;

    public AuthController(AuthService authService, CurrentUser currentUser) {
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new user and open a virtual account funded with the initial balance")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange credentials for a JWT access token")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @GetMapping("/me")
    @Operation(summary = "Current authenticated profile")
    public ResponseEntity<UserProfileDto> me() {
        UserPrincipal principal = currentUser.require();
        return ResponseEntity.ok(authService.profile(principal.getUserId()));
    }

    /**
     * Stateless logout. The token is not denylisted, so the client discards it. Documented here so the
     * contract is explicit rather than implied.
     */
    @PostMapping("/logout")
    @Operation(summary = "Log out (client discards the stateless token)")
    public ResponseEntity<LogoutResponse> logout() {
        currentUser.require();
        return ResponseEntity.ok(new LogoutResponse(true,
                "Signed out. Discard the access token; it is stateless and expires automatically."));
    }
}

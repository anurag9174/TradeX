package com.tradex.service;

import com.tradex.config.SecurityProperties;
import com.tradex.config.TradingProperties;
import com.tradex.dto.auth.AuthResponse;
import com.tradex.dto.auth.LoginRequest;
import com.tradex.dto.auth.RegisterRequest;
import com.tradex.dto.auth.UserProfileDto;
import com.tradex.entity.User;
import com.tradex.enums.Role;
import com.tradex.exception.ConflictException;
import com.tradex.exception.ResourceNotFoundException;
import com.tradex.exception.UnauthorizedException;
import com.tradex.repository.UserRepository;
import com.tradex.security.JwtService;
import com.tradex.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

/**
 * Registration, login and profile.
 *
 * <p>Password hashing uses BCrypt with strength 12. Login answers with the same generic message for
 * an unknown user and a wrong password so the endpoint cannot be used to enumerate accounts.</p>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AccountService accountService;
    private final WatchlistService watchlistService;
    private final SecurityProperties securityProperties;
    private final TradingProperties tradingProperties;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       AccountService accountService,
                       WatchlistService watchlistService,
                       SecurityProperties securityProperties,
                       TradingProperties tradingProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.accountService = accountService;
        this.watchlistService = watchlistService;
        this.securityProperties = securityProperties;
        this.tradingProperties = tradingProperties;
    }

    /**
     * Creates a user and their virtual trading account in one transaction. The account is funded with
     * exactly one {@code INITIAL_GRANT} of {@code tradex.trading.initial-balance}.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String username = request.username().trim();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("EMAIL_TAKEN", "An account with that email already exists.");
        }
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new ConflictException("USERNAME_TAKEN", "That username is already taken.");
        }

        User user = new User(email, username,
                request.fullName() == null || request.fullName().isBlank() ? username : request.fullName().trim(),
                passwordEncoder.encode(request.password()),
                Role.USER);
        User saved = userRepository.saveAndFlush(user);

        var account = accountService.openAccount(saved, request.initialCapital());
        watchlistService.seedDefaults(account);

        log.info("Registered user {} with virtual account {} funded with {} {}",
                saved.getUsername(), account.getAccountNumber(),
                account.getInitialBalance().toPlainString(), account.getCurrency());

        return issueToken(saved);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String identifier = request.identifier().trim();
        User user = userRepository.findByEmailIgnoreCase(identifier)
                .or(() -> userRepository.findByUsernameIgnoreCase(identifier))
                .orElseThrow(() -> new UnauthorizedException("Incorrect credentials. Please try again."));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Incorrect credentials. Please try again.");
        }
        if (!user.isEnabled()) {
            throw new UnauthorizedException("This account has been disabled.");
        }
        return issueToken(user);
    }

    @Transactional(readOnly = true)
    public UserProfileDto profile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "User was not found."));
        return toProfile(user);
    }

    public UserProfileDto toProfile(User user) {
        return new UserProfileDto(user.getId(), user.getEmail(), user.getUsername(), user.getFullName(),
                user.getRole(), user.getCreatedAt());
    }

    /** Creates the configured demo user when {@code tradex.security.seed-demo-user} is enabled. */
    @Transactional
    public void seedDemoUserIfConfigured() {
        if (!securityProperties.isSeedDemoUser()) {
            return;
        }
        String username = securityProperties.getDemoUsername();
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            return;
        }
        User user = new User(securityProperties.getDemoEmail(), username, "Demo Trader",
                passwordEncoder.encode(securityProperties.getDemoPassword()), Role.USER);
        User saved = userRepository.saveAndFlush(user);
        var account = accountService.openAccount(saved);
        watchlistService.seedDefaults(account);
        log.warn("Seeded demo user '{}' with password '{}' - development only, never enable in production.",
                username, securityProperties.getDemoPassword());
    }

    public UserPrincipal toPrincipal(User user) {
        return UserPrincipal.from(user);
    }

    private AuthResponse issueToken(User user) {
        String token = jwtService.issue(user);
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(jwtService.expirySeconds());
        return new AuthResponse(token, "Bearer", jwtService.expirySeconds(), issuedAt, expiresAt, toProfile(user));
    }
}

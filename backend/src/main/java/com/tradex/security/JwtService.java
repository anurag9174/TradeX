package com.tradex.security;

import com.tradex.config.SecurityProperties;
import com.tradex.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/** Issues and verifies stateless HS256 access tokens. */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecurityProperties properties;
    private final SecretKey key;

    public JwtService(SecurityProperties properties) {
        this.properties = properties;
        byte[] secret = properties.getJwtSecret() == null
                ? new byte[0]
                : properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "tradex.security.jwt-secret must be at least 32 bytes. "
                            + "Set the TRADEX_JWT_SECRET environment variable to a long random value.");
        }
        this.key = Keys.hmacShaKeyFor(secret);
    }

    public String issue(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofMinutes(properties.getJwtExpiryMinutes()));
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .issuer(properties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim("username", user.getUsername())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .signWith(key)
                .compact();
    }

    public Instant expiryOf(String token) {
        return parse(token).getExpiration().toInstant();
    }

    public long expirySeconds() {
        return Duration.ofMinutes(properties.getJwtExpiryMinutes()).toSeconds();
    }

    /** @return the user id encoded in the subject, or {@code null} when the token is not valid. */
    public Long extractUserId(String token) {
        try {
            Claims claims = parse(token);
            return Long.valueOf(claims.getSubject());
        } catch (Exception ex) {
            log.debug("Rejected JWT: {}", ex.getMessage());
            return null;
        }
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }
}

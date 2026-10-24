package com.tradex.config;

import com.tradex.security.JwtAuthenticationFilter;
import com.tradex.security.RateLimitFilter;
import com.tradex.security.RestSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Stateless JWT security.
 *
 * <ul>
 *   <li>No HTTP session, no CSRF token - every request carries a bearer token.</li>
 *   <li>Market data endpoints are public reads (they carry no user data).</li>
 *   <li>Everything under /api/account, /api/orders, /api/portfolio, /api/trades and /api/risk
 *       requires an authenticated principal, and the services behind them scope every query to
 *       that principal's own account.</li>
 *   <li>Actuator is limited to health/info, and admin-only endpoints require ROLE_ADMIN.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final SecurityProperties securityProperties;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final RestSecurityHandlers restSecurityHandlers;

    public SecurityConfig(SecurityProperties securityProperties,
                          JwtAuthenticationFilter jwtAuthenticationFilter,
                          RateLimitFilter rateLimitFilter,
                          RestSecurityHandlers restSecurityHandlers) {
        this.securityProperties = securityProperties;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.restSecurityHandlers = restSecurityHandlers;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Strength 12: slow enough to make offline cracking expensive, fast enough for login UX.
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // Mitigate user-enumeration timing differences.
        provider.setHideUserNotFoundExceptions(true);
        return provider::authenticate;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(restSecurityHandlers)
                        .accessDeniedHandler(restSecurityHandlers))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                        .contentTypeOptions(options -> {
                        }))
                .authorizeHttpRequests(auth -> auth
                        // --- public ---------------------------------------------------------
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // market data is a public read; it contains no user-specific information
                        .requestMatchers(HttpMethod.GET, "/api/market/**").permitAll()
                        // websocket handshake; subscriptions are read-only market data
                        .requestMatchers("/ws/**").permitAll()
                        // --- authenticated --------------------------------------------------
                        .requestMatchers("/api/auth/me", "/api/auth/logout").authenticated()
                        .requestMatchers("/api/account/**").authenticated()
                        .requestMatchers("/api/orders/**").authenticated()
                        .requestMatchers("/api/portfolio/**").authenticated()
                        .requestMatchers("/api/trades/**").authenticated()
                        .requestMatchers("/api/risk/**").authenticated()
                        .requestMatchers("/api/watchlist/**").authenticated()
                        // --- admin -----------------------------------------------------------
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = Arrays.stream(securityProperties.getAllowedOrigins().split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        // Explicit origins (never "*" with credentials) taken from configuration.
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Requested-With",
                "Idempotency-Key"));
        configuration.setExposedHeaders(List.of("Content-Disposition", "X-Total-Count"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        source.registerCorsConfiguration("/ws/**", configuration);
        return source;
    }
}

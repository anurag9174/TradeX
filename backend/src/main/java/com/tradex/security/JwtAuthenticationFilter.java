package com.tradex.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Resolves a bearer token into an authenticated security context for the current request. */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final TradexUserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtService jwtService, TradexUserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = header.substring(BEARER.length()).trim();
        if (token.isEmpty() || SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }
        Long userId = jwtService.extractUserId(token);
        if (userId == null) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            UserDetails principal = userDetailsService.loadUserById(userId);
            if (principal.isEnabled()) {
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (RuntimeException ex) {
            // Unknown / deleted user: leave the context anonymous so the entry point answers 401.
            SecurityContextHolder.clearContext();
        }
        filterChain.doFilter(request, response);
    }
}

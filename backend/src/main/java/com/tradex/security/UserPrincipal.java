package com.tradex.security;

import com.tradex.entity.User;
import com.tradex.enums.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Authenticated principal.
 *
 * <p>Every request is resolved back to a database user, and every service derives the trading
 * account from {@link #getUserId()}. Controllers never accept an account id from the client, which
 * is what makes cross-user access structurally impossible rather than merely forbidden.</p>
 */
public class UserPrincipal implements UserDetails {

    private final Long userId;
    private final String username;
    private final String email;
    private final String passwordHash;
    private final Role role;
    private final boolean enabled;

    public UserPrincipal(Long userId, String username, String email, String passwordHash, Role role, boolean enabled) {
        this.userId = userId;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
    }

    public static UserPrincipal from(User user) {
        return new UserPrincipal(user.getId(), user.getUsername(), user.getEmail(),
                user.getPasswordHash(), user.getRole(), user.isEnabled());
    }

    public Long getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}

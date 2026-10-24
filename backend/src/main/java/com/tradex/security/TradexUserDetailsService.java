package com.tradex.security;

import com.tradex.entity.User;
import com.tradex.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradexUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public TradexUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsernameIgnoreCase(username)
                .or(() -> userRepository.findByEmailIgnoreCase(username))
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));
        return UserPrincipal.from(user);
    }

    @Transactional(readOnly = true)
    public UserDetails loadUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user id: " + id));
        return UserPrincipal.from(user);
    }
}

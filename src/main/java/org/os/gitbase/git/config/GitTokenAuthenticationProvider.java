package org.os.gitbase.git.config;

import org.os.gitbase.git.entity.GitToken;
import org.os.gitbase.git.repository.GitTokenRepository;
import org.os.gitbase.git.service.CommandGitService;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class GitTokenAuthenticationProvider implements AuthenticationProvider {

    private final GitTokenRepository gitTokenRepository;
    private final PasswordEncoder passwordEncoder;

    public GitTokenAuthenticationProvider(GitTokenRepository gitTokenRepository,
                                          PasswordEncoder passwordEncoder) {
        this.gitTokenRepository = gitTokenRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        System.out.println("=== GitTokenAuthenticationProvider.authenticate() called ===");
        String username = authentication.getName();
        String token = authentication.getCredentials().toString();
        System.out.println("Username: " + username);
        System.out.println("Token: " + token.substring(0, Math.min(8, token.length())) + "...");

        // Inline validation to avoid circular dependency
        List<GitToken> tokens = gitTokenRepository.findByUsername(username);
        System.out.println("Found " + tokens.size() + " tokens for user: " + username);

        boolean isValid = tokens.stream()
                .anyMatch(t -> {
                    boolean matches = passwordEncoder.matches(token, t.getTokenHash());
                    boolean notExpired = (t.getExpiresAt() == null || t.getExpiresAt().isAfter(LocalDateTime.now()));
                    System.out.println("Token matches: " + matches + ", Not expired: " + notExpired);
                    return matches && notExpired;
                });

        if (isValid) {
            System.out.println("Authentication successful for user: " + username);
            List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_GIT_USER"));
            return new UsernamePasswordAuthenticationToken(username, token, authorities);
        } else {
            System.out.println("Authentication failed for user: " + username);
            throw new BadCredentialsException("Invalid git token");
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
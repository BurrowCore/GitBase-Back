package org.os.gitbase.git.config;

import lombok.extern.slf4j.Slf4j;
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

@Slf4j
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
        String username = authentication.getName();
        String token = authentication.getCredentials().toString();

        // Inline validation to avoid circular dependency
        List<GitToken> tokens = gitTokenRepository.findByUsername(username);

        boolean isValid = tokens.stream()
                .anyMatch(t -> passwordEncoder.matches(token, t.getTokenHash())
                        && (t.getExpiresAt() == null || t.getExpiresAt().isAfter(LocalDateTime.now())));

        if (isValid) {
            // Never log the token, nor any part of it.
            log.debug("Git token authentication succeeded for {}", username);
            List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_GIT_USER"));
            return new UsernamePasswordAuthenticationToken(username, token, authorities);
        }
        log.debug("Git token authentication failed for {}", username);
        throw new BadCredentialsException("Invalid git token");
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
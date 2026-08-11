package org.os.gitbase.git.config;

import org.os.gitbase.git.entity.GitToken;
import org.os.gitbase.git.repository.GitTokenRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class GitTokenValidationService {

    private final GitTokenRepository repo;
    private final PasswordEncoder passwordEncoder;

    public GitTokenValidationService(GitTokenRepository repo, PasswordEncoder passwordEncoder) {
        this.repo = repo;
        this.passwordEncoder = passwordEncoder;
    }

    public boolean validate(String username, String rawToken) {
        List<GitToken> tokens = repo.findByUsername(username);

        return tokens.stream()
                .anyMatch(t -> passwordEncoder.matches(rawToken, t.getTokenHash())
                        && (t.getExpiresAt() == null || t.getExpiresAt().isAfter(LocalDateTime.now())));
    }
}

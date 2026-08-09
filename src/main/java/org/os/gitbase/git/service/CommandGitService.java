package org.os.gitbase.git.service;

import lombok.extern.slf4j.Slf4j;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.exception.AccessDeniedDomainException;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.git.dto.GitTokenInfo;
import org.os.gitbase.git.entity.GitToken;
import org.os.gitbase.git.mapper.GitTokenMapper;
import org.os.gitbase.git.repository.GitTokenRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Manages Git personal access tokens (create / validate / list / revoke).
 *
 * <p>All Git smart-HTTP transport (info/refs, upload-pack, receive-pack) is served exclusively by
 * the JGit {@code GitServlet} mounted at {@code /gitbase/*} — see {@code GitServletConfig},
 * {@code GitbaseRepositoryResolver} (read gate + path jail) and {@code GitbaseReceivePackFactory}
 * (write gate). The previous manual pack handlers were removed in favour of that single path.
 */
@Service
@Slf4j
public class CommandGitService {

    private final GitTokenRepository repo;
    private final GitTokenMapper gitTokenMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;

    public CommandGitService(GitTokenRepository repo, PasswordEncoder passwordEncoder,
                             UserRepository userRepository, GitTokenMapper gitTokenMapper) {
        this.repo = repo;
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
        this.gitTokenMapper = gitTokenMapper;
    }

    public String createToken(String user, String name, String scopes, Duration validity) {
        String rawToken = UUID.randomUUID().toString().replace("-", "");
        String hash = passwordEncoder.encode(rawToken); // uses Argon2

        GitToken entity = new GitToken();
        entity.setUser(userRepository.findUserByName(user).get());
        entity.setName(name);
        entity.setTokenHash(hash);
        entity.setScopes(scopes);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setExpiresAt(LocalDateTime.now().plus(validity));

        repo.save(entity);

        return rawToken; // only return once
    }

    public boolean validate(String username, String rawToken) {
        List<GitToken> tokens = repo.findByUsername(username);

        return tokens.stream()
                .anyMatch(t -> passwordEncoder.matches(rawToken, t.getTokenHash())
                        && (t.getExpiresAt() == null || t.getExpiresAt().isAfter(LocalDateTime.now())));
    }

    public List<GitTokenInfo> getTokens(String username) {
        List<GitToken> tokens = repo.findByUsername(username);
        return gitTokenMapper.toDtoList(tokens);
    }

    /**
     * Revokes (deletes) a personal access token owned by {@code username}.
     *
     * @throws ResourceNotFoundException   if no token exists with that id
     * @throws AccessDeniedDomainException if the token belongs to another user
     */
    public void revokeToken(String username, Long tokenId) {
        GitToken token = repo.findById(tokenId)
                .orElseThrow(() -> new ResourceNotFoundException("Token not found: " + tokenId));

        if (token.getUser() == null || !username.equals(token.getUser().getName())) {
            throw new AccessDeniedDomainException("Token does not belong to the current user");
        }

        repo.delete(token);
        log.info("Revoked token id={} for user={}", tokenId, username);
    }
}

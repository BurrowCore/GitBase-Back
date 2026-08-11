package org.os.gitbase.git.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.os.gitbase.auth.entity.User;

import java.time.LocalDateTime;

@Entity
@Table(name = "git_tokens")
@Getter
@Setter
public class GitToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false) // foreign key
    private User user;

    private String name; // user chooses e.g. "My laptop token"

    private String tokenHash; // store hashed token

    private String scopes; // e.g. "repo:read,repo:write"

    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;
}


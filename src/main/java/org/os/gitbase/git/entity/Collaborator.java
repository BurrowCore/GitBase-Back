package org.os.gitbase.git.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.git.entity.enums.RepositoryPermission;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Grants a user a permission level on someone else's repository.
 *
 * <p>Follows Gitea's {@code repo_collaborator} shape: a direct user→repository grant with one
 * permission level, unique per pair. Access is effective immediately — Gitea has no
 * invitation/acceptance step (unlike GitHub), and neither do we.
 *
 * <p>The repository owner is never stored here; ownership lives on {@code RepositoryGit.owner}
 * and always outranks any collaborator grant.
 */
@Entity
@Table(
        name = "repository_collaborators",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_collaborator_repo_user",
                columnNames = {"repository_id", "user_id"}
        ),
        indexes = {
                @Index(name = "idx_collaborator_repo", columnList = "repository_id"),
                @Index(name = "idx_collaborator_user", columnList = "user_id")
        }
)
@Getter
@Setter
public class Collaborator {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private RepositoryGit repository;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RepositoryPermission permission;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

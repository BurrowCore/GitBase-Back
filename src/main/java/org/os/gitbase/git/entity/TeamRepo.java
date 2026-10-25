package org.os.gitbase.git.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A repository explicitly granted to a team. Not used for teams that
 * include all repositories — that is a flag on {@link Team}, evaluated at read time.
 */
@Entity
@Table(
        name = "team_repositories",
        uniqueConstraints = @UniqueConstraint(name = "uk_team_repository", columnNames = {"team_id", "repository_id"}),
        indexes = @Index(name = "idx_team_repository_repo", columnList = "repository_id")
)
@Getter
@Setter
public class TeamRepo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private RepositoryGit repository;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}

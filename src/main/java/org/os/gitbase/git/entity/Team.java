package org.os.gitbase.git.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.git.entity.enums.RepositoryPermission;

import java.time.LocalDateTime;

/**
 * A group of an organization's members with one permission level on a set of its repositories.
 *
 * <p>The <b>Owners</b> team is created with the organization and flagged {@link #ownerTeam}
 * (a flag, not a name, so renaming a team can never grant or remove ownership). Its members are the
 * organization's owners: they may do anything, including deleting repositories and managing the
 * organization. It cannot be deleted and must always keep at least one member.
 *
 * <p>{@link #includesAllRepositories} is evaluated <b>at read time</b>: no {@code team_repositories}
 * rows are written for it, so setting it never costs work proportional to the organization's size
 * (ADR-004), and repositories created later are covered automatically.
 */
@Entity
@Table(
        name = "teams",
        uniqueConstraints = @UniqueConstraint(name = "uk_team_org_lower_name", columnNames = {"organization_id", "lower_name"}),
        indexes = @Index(name = "idx_team_org", columnList = "organization_id")
)
@Getter
@Setter
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The organization — a {@code t_users} row of type ORGANIZATION. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private User organization;

    @Column(nullable = false, length = 100)
    private String name;

    /** Lower-cased name: team names are unique per organization ignoring case. */
    @Column(name = "lower_name", nullable = false, length = 100)
    private String lowerName;

    @Column(length = 500)
    private String description;

    /** Access the team grants on its repositories. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RepositoryPermission permission = RepositoryPermission.READ;

    @Column(name = "includes_all_repositories", nullable = false)
    private boolean includesAllRepositories;

    /** Members may create repositories in the organization. */
    @Column(name = "can_create_org_repo", nullable = false)
    private boolean canCreateOrgRepo;

    @Column(name = "owner_team", nullable = false)
    private boolean ownerTeam;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (name != null) lowerName = name.toLowerCase(java.util.Locale.ROOT);
    }

    @PreUpdate
    void onUpdate() {
        if (name != null) lowerName = name.toLowerCase(java.util.Locale.ROOT);
    }
}

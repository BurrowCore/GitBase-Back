package org.os.gitbase.git.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.os.gitbase.auth.entity.User;

import java.time.LocalDateTime;

/**
 * "{@link #issue} depends on {@link #dependency}" — equivalently, the dependency <i>blocks</i> the
 * issue. One row per pair, read in either direction — there is no direction column.
 *
 * <p>Same-repository only for now.
 */
@Entity
@Table(
        name = "issue_dependencies",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_issue_dependency_pair",
                columnNames = {"issue_id", "dependency_id"}
        ),
        indexes = @Index(name = "idx_issue_dependency_dependency", columnList = "dependency_id")
)
@Getter
@Setter
public class IssueDependency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The blocked issue. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issue_id", nullable = false)
    private Issue issue;

    /** The issue that must be closed first. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dependency_id", nullable = false)
    private Issue dependency;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}

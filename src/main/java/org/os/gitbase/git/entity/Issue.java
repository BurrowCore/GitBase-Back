package org.os.gitbase.git.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.git.entity.enums.IssueState;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A tracked issue.
 *
 * <p><b>Numbering:</b> {@code number} comes from the repository-wide allocator shared with pull
 * requests, so issues and PRs interleave in one sequence exactly as they do on GitHub and Gitea.
 * A repository therefore never has both an issue #1 and a pull request #1, which is what makes a
 * bare "#12" reference unambiguous.
 *
 * <p>Unlike Gitea we keep issues and pull requests in <b>separate tables</b> (Gitea stores a PR as
 * an issue row with {@code is_pull=true}); only the number sequence is shared. That was a
 * deliberate call to avoid rewriting the existing pull-request feature — see DECISION-008.
 */
@Entity
@Table(
        name = "issues",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_issue_repo_number",
                columnNames = {"repository_id", "number"}
        ),
        indexes = {
                @Index(name = "idx_issue_repo_state", columnList = "repository_id,state")
        }
)
@Getter
@Setter
public class Issue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private RepositoryGit repository;

    /** Shared per-repository sequence — see the class javadoc. */
    @Column(nullable = false)
    private int number;

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false)
    private String title;

    @Size(max = 65535)
    @Column(columnDefinition = "TEXT")
    private String body;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IssueState state = IssueState.OPEN;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "issue_labels",
            joinColumns = @JoinColumn(name = "issue_id"),
            inverseJoinColumns = @JoinColumn(name = "label_id")
    )
    private Set<Label> labels = new LinkedHashSet<>();

    /** Gitea supports multiple assignees; so do we. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "issue_assignees",
            joinColumns = @JoinColumn(name = "issue_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    private Set<User> assignees = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by_id")
    private User closedBy;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (state == null) state = IssueState.OPEN;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

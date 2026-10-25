package org.os.gitbase.git.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.git.entity.enums.MergeMethod;
import org.os.gitbase.git.entity.enums.PullRequestStatus;

import java.time.LocalDateTime;

/**
 * A proposal to merge one branch into another within the same repository.
 *
 * <p><b>Branches are stored as names, not foreign keys.</b> Git is the source of truth for refs
 * (ADR-004: the database is an incrementally-maintained mirror), so pointing at a {@code Branch}
 * row would break the moment a branch is deleted or re-created outside the app — and would make
 * historical pull requests unreadable. The {@code baseSha}/{@code headSha} snapshots record where
 * the branches pointed when the pull request was opened, which is what makes a closed PR still
 * meaningful after the branch has moved on.
 */
@Entity
@Table(
        name = "pull_requests",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pull_request_repo_number",
                columnNames = {"repository_id", "number"}
        ),
        indexes = {
                @Index(name = "idx_pull_request_repo_status", columnList = "repository_id,status")
        }
)
@Getter
@Setter
public class PullRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private RepositoryGit repository;

    /** Per-repository sequential number, as shown in the UI (#1, #2, …). */
    @Column(nullable = false)
    private int number;

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false)
    private String title;

    @Size(max = 65535)
    @Column(columnDefinition = "TEXT")
    private String description;

    /** Branch the changes come FROM. */
    @Column(name = "source_branch", nullable = false)
    private String sourceBranch;

    /** Branch the changes will be merged INTO. */
    @Column(name = "target_branch", nullable = false)
    private String targetBranch;

    /** Tip of {@link #sourceBranch} when the pull request was opened. */
    @Column(name = "head_sha", length = 40)
    private String headSha;

    /** Tip of {@link #targetBranch} when the pull request was opened. */
    @Column(name = "base_sha", length = 40)
    private String baseSha;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PullRequestStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "merged_at")
    private LocalDateTime mergedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merged_by_id")
    private User mergedBy;

    /** SHA of the merge commit created on the target branch. Null until merged. */
    @Column(name = "merge_commit_sha", length = 40)
    private String mergeCommitSha;

    /**
     * How the pull request was landed. Null until merged, and for rows merged before merge methods
     * existed (all of which were merge commits). Kept alongside the merge commit id
     * so the UI can say "squashed" or "rebased" without inspecting git history.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "merge_method", length = 16)
    private MergeMethod mergeMethod;

    /**
     * Optimistic lock. The merge runs its git work outside any transaction, so two concurrent
     * merge requests can both pass the OPEN check; only the first to commit wins, the other gets
     * a 409 instead of overwriting {@code mergedBy}/{@code mergeCommitSha}. Existing rows start
     * at 0 via the column default.
     */
    @Version
    @Column(name = "version", columnDefinition = "bigint default 0")
    private Long version;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = PullRequestStatus.OPEN;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

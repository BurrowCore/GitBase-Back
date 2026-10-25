package org.os.gitbase.git.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.os.gitbase.auth.entity.User;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "repositories")
@Getter
@Setter
public class RepositoryGit {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User owner;

    @NotBlank
    @Size(max = 255)
    private String repoName;

    @JsonProperty("isPrivate")
    private boolean isPrivate;

    /**
     * Highest #number handed out in this repository. Issues and pull requests draw from this ONE
     * sequence, so a repo reads #1, #2, #3 regardless of type — which is what makes a bare "#12" reference unambiguous.
     *
     * <p>Nullable on purpose: {@code ddl-auto: update} cannot add a NOT NULL column to a table that
     * already has rows, so the column is added nullable with a default and the allocator treats a
     * null as zero. It is self-healing anyway — it reconciles against the real maxima on each call.
     */
    @Column(name = "last_issue_number", columnDefinition = "integer default 0")
    private Integer lastIssueNumber = 0;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // getters/setters
}


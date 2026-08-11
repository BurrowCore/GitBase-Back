package org.os.gitbase.git.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.os.gitbase.auth.entity.User;
import org.springframework.data.annotation.CreatedDate;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "commits")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Commit {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id", nullable = false)
    private RepositoryGit repository;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Column(name = "commit_hash", nullable = false, unique = true, length = 64)
    private String commitHash; // SHA-1/SHA-256 of the commit

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @CreatedDate
    @Column(name = "committed_at", updatable = false)
    private LocalDateTime committedAt;

    @ManyToMany
    @JoinTable(
            name = "commit_parents",
            joinColumns = @JoinColumn(name = "commit_id"),
            inverseJoinColumns = @JoinColumn(name = "parent_id")
    )
    private Set<Commit> parents = new HashSet<>(); // DAG structure

    @OneToMany(mappedBy = "commit", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Branch> branches = new HashSet<>();
}


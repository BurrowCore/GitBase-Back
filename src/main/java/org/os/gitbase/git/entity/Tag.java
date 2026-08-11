package org.os.gitbase.git.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "tags", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"repository_id", "name"})
})
public class Tag {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id", nullable = false)
    private RepositoryGit repository;

    @Column(nullable = false, length = 40)
    private String commitSha; // The commit this tag points to

    @Column
    private String message; // Tag annotation message

    @Column(nullable = false)
    private String tagger;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    // Getters and setters
}
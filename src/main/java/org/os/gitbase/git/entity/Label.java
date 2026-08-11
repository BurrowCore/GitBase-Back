package org.os.gitbase.git.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;


/** A repository-scoped label that can be attached to issues. */
@Entity
@Table(
        name = "labels",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_label_repo_name",
                columnNames = {"repository_id", "name"}
        ),
        indexes = @Index(name = "idx_label_repo", columnList = "repository_id")
)
@Getter
@Setter
public class Label {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private RepositoryGit repository;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String name;

    /** Hex colour without the leading '#', e.g. {@code d73a4a}. */
    @Pattern(regexp = "^[0-9a-fA-F]{6}$", message = "Color must be a 6-digit hex value")
    @Column(nullable = false, length = 6)
    private String color = "cccccc";

    @Size(max = 255)
    private String description;
}

package org.os.gitbase.git.entity;

import jakarta.persistence.*;
import org.os.gitbase.git.entity.enums.PipelineStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pipeline_executions")
public class PipelineExecution {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repository_id")
    private Long repositoryId;

    @Column(name = "commit_sha")
    private String commitSha;

    @Column(name = "branch")
    private String branch;

    @Column(name = "pipeline_config")
    @Lob
    private String pipelineConfig;

    @Enumerated(EnumType.STRING)
    private PipelineStatus status;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "duration")
    private Long duration; // in seconds

    @OneToMany(mappedBy = "pipelineExecution", cascade = CascadeType.ALL)
    private List<PipelineStageExecution> stages = new ArrayList<>();

    // getters, setters, constructors
}

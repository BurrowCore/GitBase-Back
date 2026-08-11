package org.os.gitbase.git.entity;

import jakarta.persistence.*;
import org.os.gitbase.git.entity.enums.PipelineStatus;

import java.time.LocalDateTime;

@Entity
@Table(name = "pipeline_stage_executions")
public class PipelineStageExecution {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "pipeline_execution_id")
    private PipelineExecution pipelineExecution;

    @Column(name = "stage_name")
    private String stageName;

    @Column(name = "docker_image")
    private String dockerImage;

    @Enumerated(EnumType.STRING)
    private PipelineStatus status;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "logs")
    @Lob
    private String logs;

    @Column(name = "exit_code")
    private Integer exitCode;

    // getters, setters, constructors
}

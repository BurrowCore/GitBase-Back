package org.os.gitbase.git.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PipelineJobMessage {
    private Long repositoryId;
    private String repositoryName;
    private String ownerUsername;
    private String commitSha;
    private String branch;
    private String triggerType; // push, pull_request, schedule, manual
    private Map<String, Object> variables;
    private LocalDateTime createdAt;

    // getters, setters, constructors
}

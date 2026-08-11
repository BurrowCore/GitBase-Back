package org.os.gitbase.git.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class RepositoryInfo {
    private String username;
    private String repoName;
    private boolean isPrivate;
    private long lastCommitTime; // epoch millis

    public RepositoryInfo(String username, String repoName, boolean isPrivate, long lastCommitTime) {
        this.username = username;
        this.repoName = repoName;
        this.isPrivate = isPrivate;
        this.lastCommitTime = lastCommitTime;
    }

    // getters/setters
}


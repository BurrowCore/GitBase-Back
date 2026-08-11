package org.os.gitbase.git.dto;

import lombok.Getter;
import lombok.Setter;

/** A single entry (file or directory) in a directory listing, with the commit that last touched it. */
@Getter
@Setter
public class DirEntryDto {
    private String name;
    private String path;            // full path from repo root
    private String type;            // "dir" or "file"
    private String lastCommitMessage;
    private String lastCommitSha;
    private long lastCommitDate;    // epoch millis (0 if unknown)

    public DirEntryDto(String name, String path, String type,
                       String lastCommitMessage, String lastCommitSha, long lastCommitDate) {
        this.name = name;
        this.path = path;
        this.type = type;
        this.lastCommitMessage = lastCommitMessage;
        this.lastCommitSha = lastCommitSha;
        this.lastCommitDate = lastCommitDate;
    }
}

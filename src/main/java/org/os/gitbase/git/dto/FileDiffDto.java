package org.os.gitbase.git.dto;

import lombok.Getter;
import lombok.Setter;

/** A single file's change within a commit (or compare) diff. */
@Getter
@Setter
public class FileDiffDto {
    private String path;        // new path (old path for deletes)
    private String oldPath;     // previous path for renames/copies; null otherwise
    private String changeType;  // ADD, MODIFY, DELETE, RENAME, COPY
    private int additions;
    private int deletions;
    private boolean binary;
    private String patch;       // unified diff text; null when binary

    public FileDiffDto(String path, String oldPath, String changeType,
                       int additions, int deletions, boolean binary, String patch) {
        this.path = path;
        this.oldPath = oldPath;
        this.changeType = changeType;
        this.additions = additions;
        this.deletions = deletions;
        this.binary = binary;
        this.patch = patch;
    }
}

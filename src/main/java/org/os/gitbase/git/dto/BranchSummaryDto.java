package org.os.gitbase.git.dto;

import lombok.Getter;
import lombok.Setter;

/** A branch as it exists on disk: name + head commit. Read live from JGit refs. */
@Getter
@Setter
public class BranchSummaryDto {
    private String name;
    private String commitSha;
    private String shortSha;
    private boolean isDefault; // true when this branch is the repo's HEAD target

    public BranchSummaryDto(String name, String commitSha, String shortSha, boolean isDefault) {
        this.name = name;
        this.commitSha = commitSha;
        this.shortSha = shortSha;
        this.isDefault = isDefault;
    }
}

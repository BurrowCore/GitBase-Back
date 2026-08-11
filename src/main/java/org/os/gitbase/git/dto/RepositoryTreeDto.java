package org.os.gitbase.git.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class RepositoryTreeDto {
    private String repoName;
    private boolean isPrivate;
    private FileTreeNode root;
    public RepositoryTreeDto() {}
    public RepositoryTreeDto(String repoName, boolean isPrivate, FileTreeNode root) {
        this.repoName = repoName;
        this.isPrivate = isPrivate;
        this.root = root;
    }
}


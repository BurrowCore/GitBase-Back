package org.os.gitbase.git.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class FileTreeNode {
    private String name;
    private boolean isDirectory;
    private List<FileTreeNode> children = new ArrayList<>();

    public FileTreeNode(String name, boolean isDirectory) {
        this.name = name;
        this.isDirectory = isDirectory;
    }
}


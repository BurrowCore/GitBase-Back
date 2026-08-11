package org.os.gitbase.git.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * GitHub-style directory listing: the entries at a given path plus the repository's
 * latest commit on the ref (for the "latest commit" bar at the top of the browser).
 */
@Getter
@Setter
public class DirectoryListingDto {
    private String path;                 // "" for repo root
    private String ref;                  // resolved ref label
    private CommitSummaryDto latestCommit; // null for an empty repo
    private List<DirEntryDto> entries;

    public DirectoryListingDto(String path, String ref, CommitSummaryDto latestCommit, List<DirEntryDto> entries) {
        this.path = path;
        this.ref = ref;
        this.latestCommit = latestCommit;
        this.entries = entries;
    }
}

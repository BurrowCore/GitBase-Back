package org.os.gitbase.git.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** One page of commit history for a ref (and optional path filter). */
@Getter
@Setter
public class CommitPageDto {
    private String ref;
    private int page;
    private int size;
    private boolean hasNext;
    private List<CommitSummaryDto> commits;

    public CommitPageDto(String ref, int page, int size, boolean hasNext, List<CommitSummaryDto> commits) {
        this.ref = ref;
        this.page = page;
        this.size = size;
        this.hasNext = hasNext;
        this.commits = commits;
    }
}

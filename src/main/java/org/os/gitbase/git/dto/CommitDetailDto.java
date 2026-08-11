package org.os.gitbase.git.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** Full commit metadata plus its per-file diff against the first parent. */
@Getter
@Setter
public class CommitDetailDto {
    private String sha;
    private String shortSha;
    private String message;       // full commit message
    private String authorName;
    private String authorEmail;
    private long date;            // epoch millis
    private List<String> parents; // parent commit SHAs
    private int additions;
    private int deletions;
    private List<FileDiffDto> files;

    public CommitDetailDto(String sha, String shortSha, String message,
                           String authorName, String authorEmail, long date,
                           List<String> parents, int additions, int deletions,
                           List<FileDiffDto> files) {
        this.sha = sha;
        this.shortSha = shortSha;
        this.message = message;
        this.authorName = authorName;
        this.authorEmail = authorEmail;
        this.date = date;
        this.parents = parents;
        this.additions = additions;
        this.deletions = deletions;
        this.files = files;
    }
}

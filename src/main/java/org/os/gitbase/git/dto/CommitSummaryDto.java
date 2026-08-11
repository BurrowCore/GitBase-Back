package org.os.gitbase.git.dto;

import lombok.Getter;
import lombok.Setter;

/** Compact commit info for listings and the "latest commit" bar. */
@Getter
@Setter
public class CommitSummaryDto {
    private String sha;
    private String shortSha;
    private String message;     // short (first line) message
    private String authorName;
    private String authorEmail;
    private long date;          // epoch millis of the commit time

    public CommitSummaryDto(String sha, String shortSha, String message,
                            String authorName, String authorEmail, long date) {
        this.sha = sha;
        this.shortSha = shortSha;
        this.message = message;
        this.authorName = authorName;
        this.authorEmail = authorEmail;
        this.date = date;
    }
}

package org.os.gitbase.git.service;

import java.util.Set;
import java.util.UUID;

/**
 * Published when a pull request has been merged. Listeners run <b>after the merge transaction
 * commits</b>, so a failure in one (e.g. closing referenced issues) can never undo a merge whose
 * commits are already on the target branch.
 *
 * @param repositoryId           the repository row id
 * @param intoDefaultBranch      whether the target was the repository's default branch — closing
 *                               keywords only act then, as on GitHub
 * @param closingIssueNumbers    numbers referenced with a closing keyword in the title/description
 */
public record PullRequestMergedEvent(UUID repositoryId, String owner, String repoName, int pullNumber,
                                     String mergedBy, boolean intoDefaultBranch,
                                     Set<Integer> closingIssueNumbers) {
}

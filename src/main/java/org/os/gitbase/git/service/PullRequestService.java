package org.os.gitbase.git.service;

import org.os.gitbase.git.dto.*;

/**
 * Pull-request lifecycle: create, browse, discuss, merge, close, reopen.
 *
 * <p>All methods take the acting user's username; authorization (who may read the repository,
 * who may merge) is enforced by the controller through {@code RepositoryAccessGuard}, while the
 * rules that depend on pull-request state (author-only close, OPEN-only merge) live here.
 */
public interface PullRequestService {

    PullRequestDto create(String owner, String repoName, String actor, CreatePullRequestDto request);

    PullRequestPageDto list(String owner, String repoName, String state, int page, int size);

    PullRequestDto get(String owner, String repoName, int number);

    /** Three-way diff of the pull request, computed live from JGit. */
    CompareDto diff(String owner, String repoName, int number);

    MergeResultDto merge(String owner, String repoName, int number, String actor, MergePullRequestDto request);

    PullRequestDto close(String owner, String repoName, int number, String actor);

    PullRequestDto reopen(String owner, String repoName, int number, String actor);

    CommentPageDto listComments(String owner, String repoName, int number, int page, int size);

    CommentDto addComment(String owner, String repoName, int number, String actor, CreateCommentDto request);
}

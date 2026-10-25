package org.os.gitbase.git.service;

import org.os.gitbase.git.dto.*;

/**
 * Pull-request lifecycle: create, browse, discuss, merge, close, reopen.
 *
 * <p>All methods take the acting user's username; authorization (who may read the repository,
 * who may merge) is enforced by the controller through {@code RepositoryAccessGuard}, while the
 * rules that depend on pull-request state (author-or-writer close, OPEN-only merge) live here.
 */
public interface PullRequestService {

    PullRequestDto create(String owner, String repoName, String actor, CreatePullRequestDto request);

    PullRequestPageDto list(String owner, String repoName, String state, int page, int size);

    PullRequestDto get(String owner, String repoName, int number);

    /** Three-way diff of the pull request, computed live from JGit. */
    CompareDto diff(String owner, String repoName, int number);

    MergeResultDto merge(String owner, String repoName, int number, String actor, MergePullRequestDto request);

    /**
     * @param hasWriteAccess whether the actor holds WRITE on the repository (owner or collaborator);
     *                       besides the pull-request author, only such users may close it
     */
    PullRequestDto close(String owner, String repoName, int number, String actor, boolean hasWriteAccess);

    PullRequestDto reopen(String owner, String repoName, int number, String actor, boolean hasWriteAccess);

    CommentPageDto listComments(String owner, String repoName, int number, int page, int size);

    CommentDto addComment(String owner, String repoName, int number, String actor, CreateCommentDto request);

    /**
     * Recently-pushed branches that have no open pull request — powers the "Compare &amp; pull
     * request" banner, so a branch created over the CLI is discoverable without digging into the
     * new-pull-request form.
     */
    java.util.List<PullSuggestionDto> suggestions(String owner, String repoName);
}

package org.os.gitbase.git.service;

import org.os.gitbase.git.dto.*;

import java.util.List;

/**
 * Issue tracking: create, browse, edit, discuss, close and reopen.
 *
 * <p>Issue numbers come from {@link IssueNumberAllocator}, the sequence shared with pull requests,
 * so a repository reads #1, #2, #3 whatever the type — matching GitHub and Gitea.
 *
 * <p>Authorization is split the same way as pull requests: repository visibility is enforced by
 * the controller through {@code RepositoryAccessGuard}, while rules that depend on the issue
 * itself (only the author or someone with write access may edit it) live here.
 */
public interface IssueService {

    IssueDto create(String owner, String repoName, String actor, CreateIssueDto request);

    IssuePageDto list(String owner, String repoName, String state, String label,
                      String assignee, String search, int page, int size);

    IssueDto get(String owner, String repoName, int number);

    /** Partial update — null fields are left unchanged. Author or write access required. */
    IssueDto update(String owner, String repoName, int number, String actor,
                    UpdateIssueDto request, boolean actorHasWriteAccess);

    IssueDto close(String owner, String repoName, int number, String actor, boolean actorHasWriteAccess);

    IssueDto reopen(String owner, String repoName, int number, String actor, boolean actorHasWriteAccess);

    CommentPageDto listComments(String owner, String repoName, int number, int page, int size);

    CommentDto addComment(String owner, String repoName, int number, String actor, CreateCommentDto request);

    // ── labels ──

    List<LabelDto> listLabels(String owner, String repoName);

    /** Creates a label, or updates the colour/description of one that already exists. */
    LabelDto saveLabel(String owner, String repoName, CreateLabelDto request);

    void deleteLabel(String owner, String repoName, String name);
}

package org.os.gitbase.git.controller;

import jakarta.validation.Valid;
import org.os.gitbase.common.ApiResponseEntity;
import org.os.gitbase.git.dto.*;
import org.os.gitbase.git.service.PullRequestService;
import org.os.gitbase.helper.Helper;
import org.os.gitbase.security.RepositoryAccessGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

import static org.os.gitbase.constant.Constant.GITBASE_MAPPING_REQUEST;

/**
 * Pull-request endpoints.
 *
 * <p><b>Path note:</b> the feature spec drafted these under {@code /api/v1/repos/...}, but every
 * other repository-scoped endpoint (and the access guard, and the frontend service layer) lives
 * under {@code /api/v1/web/gitbase/...}. Consistency won; the spec has been annotated.
 *
 * <p><b>Authorization.</b> Reading a pull request requires read access to its repository, so
 * private repositories stay private. Merging requires write access (owner-only in the current
 * permission model). Closing and reopening additionally require the caller to be the pull-request
 * author or the repository owner — that rule depends on pull-request state, so it lives in the
 * service rather than here.
 */
@RestController
@RequestMapping(GITBASE_MAPPING_REQUEST + "/{username}/{repoName}/pulls")
public class PullRequestController {

    private final PullRequestService pullRequestService;
    private final RepositoryAccessGuard accessGuard;

    public PullRequestController(PullRequestService pullRequestService, RepositoryAccessGuard accessGuard) {
        this.pullRequestService = pullRequestService;
        this.accessGuard = accessGuard;
    }

    // -------------------- LIST --------------------
    @GetMapping
    public ResponseEntity<ApiResponseEntity<PullRequestPageDto>> list(
            @PathVariable String username,
            @PathVariable String repoName,
            @RequestParam(required = false, defaultValue = "OPEN") String state,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "30") int size,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        PullRequestPageDto result = pullRequestService.list(username, repoName, state, page, size);
        return ResponseEntity.ok(ApiResponseEntity.ok(result, "Pull requests retrieved"));
    }

    // -------------------- CREATE --------------------
    @PostMapping
    public ResponseEntity<ApiResponseEntity<PullRequestDto>> create(
            @PathVariable String username,
            @PathVariable String repoName,
            @Valid @RequestBody CreatePullRequestDto request,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        PullRequestDto created = pullRequestService.create(username, repoName, actor(principal), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseEntity.created(created, "Pull request created"));
    }

    // -------------------- DETAIL --------------------
    @GetMapping("/{number}")
    public ResponseEntity<ApiResponseEntity<PullRequestDto>> get(
            @PathVariable String username,
            @PathVariable String repoName,
            @PathVariable int number,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        PullRequestDto pr = pullRequestService.get(username, repoName, number);
        return ResponseEntity.ok(ApiResponseEntity.ok(pr, "Pull request retrieved"));
    }

    // -------------------- DIFF --------------------
    @GetMapping("/{number}/diff")
    public ResponseEntity<ApiResponseEntity<CompareDto>> diff(
            @PathVariable String username,
            @PathVariable String repoName,
            @PathVariable int number,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        CompareDto diff = pullRequestService.diff(username, repoName, number);
        return ResponseEntity.ok(ApiResponseEntity.ok(diff, "Pull request diff computed"));
    }

    // -------------------- MERGE --------------------
    @PostMapping("/{number}/merge")
    public ResponseEntity<ApiResponseEntity<MergeResultDto>> merge(
            @PathVariable String username,
            @PathVariable String repoName,
            @PathVariable int number,
            @Valid @RequestBody(required = false) MergePullRequestDto request,
            Principal principal) {
        // Merging writes to the repository, so it needs write access — not just read.
        accessGuard.requireWriteAccess(username, repoName, principal);
        MergeResultDto result = pullRequestService.merge(username, repoName, number, actor(principal), request);
        return ResponseEntity.ok(ApiResponseEntity.ok(result, result.message()));
    }

    // -------------------- CLOSE --------------------
    @PostMapping("/{number}/close")
    public ResponseEntity<ApiResponseEntity<PullRequestDto>> close(
            @PathVariable String username,
            @PathVariable String repoName,
            @PathVariable int number,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        PullRequestDto pr = pullRequestService.close(username, repoName, number, actor(principal));
        return ResponseEntity.ok(ApiResponseEntity.ok(pr, "Pull request closed"));
    }

    // -------------------- REOPEN --------------------
    @PostMapping("/{number}/reopen")
    public ResponseEntity<ApiResponseEntity<PullRequestDto>> reopen(
            @PathVariable String username,
            @PathVariable String repoName,
            @PathVariable int number,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        PullRequestDto pr = pullRequestService.reopen(username, repoName, number, actor(principal));
        return ResponseEntity.ok(ApiResponseEntity.ok(pr, "Pull request reopened"));
    }

    // -------------------- COMMENTS --------------------
    @GetMapping("/{number}/comments")
    public ResponseEntity<ApiResponseEntity<CommentPageDto>> listComments(
            @PathVariable String username,
            @PathVariable String repoName,
            @PathVariable int number,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "30") int size,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        CommentPageDto comments = pullRequestService.listComments(username, repoName, number, page, size);
        return ResponseEntity.ok(ApiResponseEntity.ok(comments, "Comments retrieved"));
    }

    @PostMapping("/{number}/comments")
    public ResponseEntity<ApiResponseEntity<CommentDto>> addComment(
            @PathVariable String username,
            @PathVariable String repoName,
            @PathVariable int number,
            @Valid @RequestBody CreateCommentDto request,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        CommentDto comment = pullRequestService.addComment(username, repoName, number, actor(principal), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseEntity.created(comment, "Comment added"));
    }

    /** The acting username, normalised the same way the rest of the app does (email → local part). */
    private String actor(Principal principal) {
        return Helper.removeAtSymbolAndFollowing(principal.getName());
    }
}

package org.os.gitbase.git.controller;

import jakarta.validation.Valid;
import org.os.gitbase.common.ApiResponseEntity;
import org.os.gitbase.git.dto.*;
import org.os.gitbase.git.entity.enums.RepositoryPermission;
import org.os.gitbase.git.service.IssueService;
import org.os.gitbase.helper.Helper;
import org.os.gitbase.security.RepositoryAccessGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

import static org.os.gitbase.constant.Constant.GITBASE_MAPPING_REQUEST;

/**
 * Issue tracker endpoints.
 *
 * <p><b>Numbering:</b> issues share one {@code #number} sequence with pull requests, so a
 * repository reads #1, #2, #3 whatever the type — the behaviour GitHub and Gitea both have.
 *
 * <p><b>Authorization:</b> reading follows repository visibility. Any user who can read may open
 * an issue and comment (as on GitHub). Editing, closing and reopening require being the issue
 * author <i>or</i> holding write access — that rule needs the issue itself, so the controller
 * resolves the caller's permission and passes it down. Managing labels is a write-level action.
 */
@RestController
@RequestMapping(GITBASE_MAPPING_REQUEST + "/{username}/{repoName}/issues")
public class IssueController {

    private final IssueService issueService;
    private final RepositoryAccessGuard accessGuard;

    public IssueController(IssueService issueService, RepositoryAccessGuard accessGuard) {
        this.issueService = issueService;
        this.accessGuard = accessGuard;
    }

    // -------------------- LIST --------------------
    @GetMapping
    public ResponseEntity<ApiResponseEntity<IssuePageDto>> list(
            @PathVariable String username,
            @PathVariable String repoName,
            @RequestParam(required = false, defaultValue = "OPEN") String state,
            @RequestParam(required = false) String label,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "30") int size,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        IssuePageDto result = issueService.list(username, repoName, state, label, assignee, search, page, size);
        return ResponseEntity.ok(ApiResponseEntity.ok(result, "Issues retrieved"));
    }

    // -------------------- CREATE --------------------
    @PostMapping
    public ResponseEntity<ApiResponseEntity<IssueDto>> create(
            @PathVariable String username,
            @PathVariable String repoName,
            @Valid @RequestBody CreateIssueDto request,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        IssueDto created = issueService.create(username, repoName, actor(principal), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseEntity.created(created, "Issue created"));
    }

    // -------------------- LABELS --------------------
    // Declared before /{number} so the literal path wins the mapping.

    @GetMapping("/labels")
    public ResponseEntity<ApiResponseEntity<List<LabelDto>>> listLabels(
            @PathVariable String username,
            @PathVariable String repoName,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        return ResponseEntity.ok(ApiResponseEntity.ok(
                issueService.listLabels(username, repoName), "Labels retrieved"));
    }

    /** Creates a label, or recolours one that already exists. */
    @PutMapping("/labels")
    public ResponseEntity<ApiResponseEntity<LabelDto>> saveLabel(
            @PathVariable String username,
            @PathVariable String repoName,
            @Valid @RequestBody CreateLabelDto request,
            Principal principal) {
        accessGuard.requireWriteAccess(username, repoName, principal);
        return ResponseEntity.ok(ApiResponseEntity.ok(
                issueService.saveLabel(username, repoName, request), "Label saved"));
    }

    @DeleteMapping("/labels")
    public ResponseEntity<ApiResponseEntity<Void>> deleteLabel(
            @PathVariable String username,
            @PathVariable String repoName,
            @RequestParam String name,
            Principal principal) {
        accessGuard.requireWriteAccess(username, repoName, principal);
        issueService.deleteLabel(username, repoName, name);
        return ResponseEntity.ok(ApiResponseEntity.message("Label deleted", HttpStatus.OK));
    }

    // -------------------- DETAIL --------------------
    @GetMapping("/{number}")
    public ResponseEntity<ApiResponseEntity<IssueDto>> get(
            @PathVariable String username,
            @PathVariable String repoName,
            @PathVariable int number,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        return ResponseEntity.ok(ApiResponseEntity.ok(
                issueService.get(username, repoName, number), "Issue retrieved"));
    }

    // -------------------- EDIT --------------------
    @PatchMapping("/{number}")
    public ResponseEntity<ApiResponseEntity<IssueDto>> update(
            @PathVariable String username,
            @PathVariable String repoName,
            @PathVariable int number,
            @Valid @RequestBody UpdateIssueDto request,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        IssueDto updated = issueService.update(username, repoName, number, actor(principal),
                request, hasWriteAccess(username, repoName, principal));
        return ResponseEntity.ok(ApiResponseEntity.ok(updated, "Issue updated"));
    }

    // -------------------- CLOSE / REOPEN --------------------
    @PostMapping("/{number}/close")
    public ResponseEntity<ApiResponseEntity<IssueDto>> close(
            @PathVariable String username,
            @PathVariable String repoName,
            @PathVariable int number,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        IssueDto issue = issueService.close(username, repoName, number, actor(principal),
                hasWriteAccess(username, repoName, principal));
        return ResponseEntity.ok(ApiResponseEntity.ok(issue, "Issue closed"));
    }

    @PostMapping("/{number}/reopen")
    public ResponseEntity<ApiResponseEntity<IssueDto>> reopen(
            @PathVariable String username,
            @PathVariable String repoName,
            @PathVariable int number,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        IssueDto issue = issueService.reopen(username, repoName, number, actor(principal),
                hasWriteAccess(username, repoName, principal));
        return ResponseEntity.ok(ApiResponseEntity.ok(issue, "Issue reopened"));
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
        return ResponseEntity.ok(ApiResponseEntity.ok(
                issueService.listComments(username, repoName, number, page, size), "Comments retrieved"));
    }

    @PostMapping("/{number}/comments")
    public ResponseEntity<ApiResponseEntity<CommentDto>> addComment(
            @PathVariable String username,
            @PathVariable String repoName,
            @PathVariable int number,
            @Valid @RequestBody CreateCommentDto request,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        CommentDto comment = issueService.addComment(username, repoName, number, actor(principal), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseEntity.created(comment, "Comment added"));
    }

    // -------------------- helpers --------------------

    /** The acting username, normalised the same way the rest of the app does (email → local part). */
    private String actor(Principal principal) {
        return Helper.removeAtSymbolAndFollowing(principal.getName());
    }

    /** Resolved once per request and handed to the service, which cannot see the Principal. */
    private boolean hasWriteAccess(String username, String repoName, Principal principal) {
        return accessGuard.effectivePermission(username, repoName, principal)
                .filter(permission -> permission.atLeast(RepositoryPermission.WRITE))
                .isPresent();
    }
}

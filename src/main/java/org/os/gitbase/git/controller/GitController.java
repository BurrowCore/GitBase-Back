package org.os.gitbase.git.controller;

import jakarta.validation.Valid;
import org.os.gitbase.common.ApiResponseEntity;
import org.os.gitbase.exception.AccessDeniedDomainException;
import org.os.gitbase.git.dto.BranchSummaryDto;
import org.os.gitbase.git.dto.CommitDetailDto;
import org.os.gitbase.git.dto.CommitPageDto;
import org.os.gitbase.git.dto.CompareDto;
import org.os.gitbase.git.dto.CreateRepositoryDto;
import org.os.gitbase.git.dto.DirectoryListingDto;
import org.os.gitbase.git.dto.FileContentDto;
import org.os.gitbase.git.dto.FileTreeNode;
import org.os.gitbase.git.dto.RawFileDto;
import org.os.gitbase.git.dto.RepositoryInfo;
import org.os.gitbase.git.dto.RepositoryTreeDto;
import org.os.gitbase.git.entity.enums.ActivityType;
import org.os.gitbase.git.service.ActivityService;
import org.os.gitbase.git.service.GitService;
import org.os.gitbase.git.service.PushSyncService;
import org.os.gitbase.helper.Helper;
import org.os.gitbase.security.RepositoryAccessGuard;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.os.gitbase.constant.Constant.*;

@RequestMapping(GITBASE_MAPPING_REQUEST)
@RestController
public class GitController {

    private final GitService gitService;
    private final ActivityService activityService;
    private final PushSyncService pushSyncService;
    private final RepositoryAccessGuard accessGuard;

    public GitController(GitService gitService, ActivityService activityService,
                         PushSyncService pushSyncService, RepositoryAccessGuard accessGuard) {
        this.gitService = gitService;
        this.activityService = activityService;
        this.pushSyncService = pushSyncService;
        this.accessGuard = accessGuard;
    }

    // -------------------- CREATE REPOSITORY --------------------
    @PostMapping(CREATE_REPOSITORY)
    public ResponseEntity<ApiResponseEntity<Map<String, Object>>> createRepository(
            @Valid @RequestBody CreateRepositoryDto request) {

        gitService.createRepository(request.getUsername(), request.getRepoName(), request.isPrivate());

        String branch = (request.getDefaultBranch() == null || request.getDefaultBranch().isEmpty())
                ? "main" : request.getDefaultBranch();
        String remoteUrl = "http://localhost:8880/api/v1/gitbase/"
                + request.getUsername() + "/" + request.getRepoName() + ".git";

        List<String> gitCommands = Arrays.asList(
                "git init",
                "git add .",
                "git branch -M " + branch,
                "git commit -m \"first commit\"",
                "git remote add origin " + remoteUrl,
                "git push -u origin " + branch
        );

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("repository", request.getRepoName());
        data.put("remoteUrl", remoteUrl);
        data.put("gitCommands", gitCommands);

        activityService.logActivity(ActivityType.REPO_CREATED, request.getUsername(), request.getRepoName(),
                "[ " + request.getRepoName() + " ] Repository created successfully");

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseEntity.created(data, "Repository created successfully"));
    }

    // -------------------- GET REPOSITORY INFO --------------------
    @GetMapping(REPOSITORY_INFO)
    public ResponseEntity<ApiResponseEntity<RepositoryInfo>> getRepositoryInfo(
            @RequestParam String username,
            @RequestParam String repoName,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        RepositoryInfo info = gitService.getRepositoryInfo(username, repoName);
        return ResponseEntity.ok(ApiResponseEntity.ok(info, "Repository info retrieved"));
    }

    // -------------------- LIST ALL USER REPOSITORIES --------------------
    @GetMapping(REPOSITORIES + "/{username}")
    public ResponseEntity<ApiResponseEntity<List<RepositoryTreeDto>>> listRepositories(
            @PathVariable String username,
            Principal principal) {
        List<RepositoryTreeDto> repos = gitService.listRepositories(username);
        // Owners see everything; others only see public repositories.
        if (!accessGuard.isOwner(username, principal)) {
            repos = repos.stream().filter(r -> !r.isPrivate()).toList();
        }
        return ResponseEntity.ok(ApiResponseEntity.ok(repos, "Repositories retrieved"));
    }

    // -------------------- BROWSE FILE TREE --------------------
    @GetMapping("/{username}/{repoName}/tree")
    public ResponseEntity<ApiResponseEntity<FileTreeNode>> getTree(
            @PathVariable String username,
            @PathVariable String repoName,
            @RequestParam(required = false) String ref,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        FileTreeNode tree = gitService.getTree(username, repoName, ref);
        return ResponseEntity.ok(ApiResponseEntity.ok(tree, "File tree retrieved"));
    }

    // -------------------- BROWSE DIRECTORY (GitHub-style listing) --------------------
    @GetMapping("/{username}/{repoName}/contents")
    public ResponseEntity<ApiResponseEntity<DirectoryListingDto>> listContents(
            @PathVariable String username,
            @PathVariable String repoName,
            @RequestParam(required = false) String ref,
            @RequestParam(required = false, defaultValue = "") String path,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        DirectoryListingDto listing = gitService.listContents(username, repoName, ref, path);
        return ResponseEntity.ok(ApiResponseEntity.ok(listing, "Directory listing retrieved"));
    }

    // -------------------- LIST BRANCHES --------------------
    @GetMapping("/{username}/{repoName}/branches")
    public ResponseEntity<ApiResponseEntity<List<BranchSummaryDto>>> listBranches(
            @PathVariable String username,
            @PathVariable String repoName,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        pushSyncService.ensureSynced(username, repoName);
        List<BranchSummaryDto> branches = gitService.listBranches(username, repoName);
        return ResponseEntity.ok(ApiResponseEntity.ok(branches, "Branches retrieved"));
    }

    // -------------------- COMPARE (PR basis) --------------------
    @GetMapping("/{username}/{repoName}/compare")
    public ResponseEntity<ApiResponseEntity<CompareDto>> compare(
            @PathVariable String username,
            @PathVariable String repoName,
            @RequestParam String base,
            @RequestParam String head,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        CompareDto result = gitService.compare(username, repoName, base, head);
        return ResponseEntity.ok(ApiResponseEntity.ok(result, "Comparison computed"));
    }

    // -------------------- COMMIT HISTORY --------------------
    @GetMapping("/{username}/{repoName}/commits")
    public ResponseEntity<ApiResponseEntity<CommitPageDto>> listCommits(
            @PathVariable String username,
            @PathVariable String repoName,
            @RequestParam(required = false) String ref,
            @RequestParam(required = false) String path,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "30") int size,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        pushSyncService.ensureSynced(username, repoName);
        CommitPageDto commits = gitService.listCommitHistory(username, repoName, ref, path, page, size);
        return ResponseEntity.ok(ApiResponseEntity.ok(commits, "Commit history retrieved"));
    }

    // -------------------- COMMIT DETAIL + DIFF --------------------
    @GetMapping("/{username}/{repoName}/commits/{sha}")
    public ResponseEntity<ApiResponseEntity<CommitDetailDto>> getCommitDetail(
            @PathVariable String username,
            @PathVariable String repoName,
            @PathVariable String sha,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        CommitDetailDto detail = gitService.getCommitDetail(username, repoName, sha);
        return ResponseEntity.ok(ApiResponseEntity.ok(detail, "Commit detail retrieved"));
    }

    // -------------------- VIEW FILE CONTENT --------------------
    @GetMapping("/{username}/{repoName}/blob")
    public ResponseEntity<ApiResponseEntity<FileContentDto>> getFileContent(
            @PathVariable String username,
            @PathVariable String repoName,
            @RequestParam(required = false) String ref,
            @RequestParam String path,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        FileContentDto file = gitService.getFileContent(username, repoName, ref, path);
        return ResponseEntity.ok(ApiResponseEntity.ok(file, "File content retrieved"));
    }

    // -------------------- RAW FILE (image preview / download) --------------------

    /**
     * Streams a blob's raw bytes. Deliberately NOT wrapped in {@link ApiResponseEntity} — this is a
     * binary transport endpoint (like git smart-HTTP), consumed directly by {@code <img src>} and
     * download links.
     *
     * <p>Hardening: the content type comes from a server-side allowlist, {@code nosniff} stops the
     * browser from re-interpreting a file as HTML, and anything not a raster image or plain text is
     * forced to download. Together these stop a repository from hosting active content on our origin.
     */
    @GetMapping("/{username}/{repoName}/raw")
    public ResponseEntity<byte[]> getRawFile(
            @PathVariable String username,
            @PathVariable String repoName,
            @RequestParam(required = false) String ref,
            @RequestParam String path,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        RawFileDto raw = gitService.getRawFile(username, repoName, ref, path);

        String fileName = raw.path().substring(raw.path().lastIndexOf('/') + 1);
        ContentDisposition disposition = (raw.inline()
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                .filename(fileName, StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, raw.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
                .body(raw.content());
    }

    // -------------------- DELETE REPOSITORY --------------------
    @DeleteMapping("/{username}/{repoName}")
    public ResponseEntity<ApiResponseEntity<Void>> deleteRepository(
            @PathVariable String username,
            @PathVariable String repoName,
            Principal principal) {

        String authUser = Helper.removeAtSymbolAndFollowing(principal.getName());
        if (!authUser.equals(username)) {
            throw new AccessDeniedDomainException("You can only delete your own repositories");
        }

        gitService.deleteRepository(authUser, repoName);
        activityService.logActivity(ActivityType.REPO_DELETED, authUser, repoName,
                "[ " + repoName + " ] Repository deleted");

        return ResponseEntity.ok(ApiResponseEntity.message("Repository deleted", HttpStatus.OK));
    }
}

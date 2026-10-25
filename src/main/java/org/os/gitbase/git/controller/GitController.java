package org.os.gitbase.git.controller;

import jakarta.validation.Valid;
import org.os.gitbase.common.ApiResponseEntity;
import org.os.gitbase.exception.AccessDeniedDomainException;
import org.os.gitbase.git.dto.BranchSummaryDto;
import org.os.gitbase.git.dto.CommitDetailDto;
import org.os.gitbase.git.dto.CommitPageDto;
import org.os.gitbase.git.dto.CompareDto;
import org.os.gitbase.git.dto.CreateBranchDto;
import org.os.gitbase.git.dto.CreateRepositoryDto;
import org.os.gitbase.git.dto.DirectoryListingDto;
import org.os.gitbase.git.dto.FileContentDto;
import org.os.gitbase.git.dto.FileTreeNode;
import org.os.gitbase.git.dto.RawFileDto;
import org.os.gitbase.git.dto.RepositoryInfo;
import org.os.gitbase.git.dto.RepositoryPermissionsDto;
import org.os.gitbase.git.dto.RepositoryTreeDto;
import org.os.gitbase.git.entity.enums.ActivityType;
import org.os.gitbase.git.service.ActivityService;
import org.os.gitbase.git.service.GitService;
import org.os.gitbase.git.service.RepositoryProvisioningService;
import org.os.gitbase.git.service.RepositoryTransferService;
import org.os.gitbase.git.dto.TransferRepositoryDto;
import org.os.gitbase.git.dto.TransferRepositoryResultDto;
import org.os.gitbase.security.CurrentUserResolver;
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
import java.util.Set;

import static org.os.gitbase.constant.Constant.*;

@RequestMapping(GITBASE_MAPPING_REQUEST)
@RestController
public class GitController {

    private final GitService gitService;
    private final ActivityService activityService;
    private final RepositoryAccessGuard accessGuard;
    private final CurrentUserResolver currentUser;
    private final RepositoryProvisioningService provisioningService;
    private final RepositoryTransferService transferService;

    public GitController(GitService gitService, ActivityService activityService,
                         RepositoryAccessGuard accessGuard, CurrentUserResolver currentUser,
                         RepositoryProvisioningService provisioningService,
                         RepositoryTransferService transferService) {
        this.gitService = gitService;
        this.activityService = activityService;
        this.accessGuard = accessGuard;
        this.currentUser = currentUser;
        this.provisioningService = provisioningService;
        this.transferService = transferService;
    }

    // -------------------- CREATE REPOSITORY --------------------
    @PostMapping(CREATE_REPOSITORY)
    public ResponseEntity<ApiResponseEntity<Map<String, Object>>> createRepository(
            @Valid @RequestBody CreateRepositoryDto request,
            Principal principal) {

        // The owner is the caller, or an organization they may create in (gitbase's POST /user/repos
        // and /orgs/{org}/repos). The body's username used to be trusted blindly, which let any
        // user create repositories inside someone else's namespace.
        String branch = (request.getDefaultBranch() == null || request.getDefaultBranch().isBlank())
                ? "main" : request.getDefaultBranch().trim();
        String owner = provisioningService.create(currentUser.requireUsername(principal), request.getUsername(),
                request.getRepoName(), request.isPrivate(), branch);
        // The JGit servlet path — the old /api/v1/gitbase/ smart-HTTP controller no longer exists.
        String remoteUrl = "http://localhost:8880/gitbase/" + owner + "/" + request.getRepoName() + ".git";

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

        activityService.logActivity(ActivityType.REPO_CREATED, owner, request.getRepoName(),
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
        info.setPermissions(RepositoryPermissionsDto.of(
                accessGuard.effectivePermission(username, repoName, principal),
                accessGuard.isOwner(username, principal)));
        return ResponseEntity.ok(ApiResponseEntity.ok(info, "Repository info retrieved"));
    }

    // -------------------- LIST ALL USER REPOSITORIES --------------------
    @GetMapping(REPOSITORIES + "/{username}")
    public ResponseEntity<ApiResponseEntity<List<RepositoryTreeDto>>> listRepositories(
            @PathVariable String username,
            Principal principal) {
        List<RepositoryTreeDto> repos = gitService.listRepositories(username);
        // Owners (and organization teams covering everything) see every repo. Everyone else sees
        // public repos plus the private ones granted to them directly or through a team — resolved
        // in a fixed number of queries rather than a check per row.
        RepositoryAccessGuard.PrivateRepoVisibility visible = accessGuard.privateRepoVisibility(username, principal);
        repos = repos.stream()
                .filter(r -> !r.isPrivate() || visible.canSee(r.getRepoName()))
                .toList();
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
        List<BranchSummaryDto> branches = gitService.listBranches(username, repoName);
        return ResponseEntity.ok(ApiResponseEntity.ok(branches, "Branches retrieved"));
    }

    // -------------------- CREATE BRANCH --------------------

    /**
     * Creates a branch directly as a git ref — there is no branch table (DECISION-007).
     * Requires write access, since it mutates the repository.
     */
    @PostMapping("/{username}/{repoName}/branches")
    public ResponseEntity<ApiResponseEntity<BranchSummaryDto>> createBranch(
            @PathVariable String username,
            @PathVariable String repoName,
            @Valid @RequestBody CreateBranchDto request,
            Principal principal) {
        accessGuard.requireWriteAccess(username, repoName, principal);
        BranchSummaryDto branch = gitService.createBranch(
                username, repoName, request.name(), request.from());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseEntity.created(branch, "Branch created"));
    }

    // -------------------- DELETE BRANCH --------------------

    /**
     * Deletes a branch ref. The name is a query parameter rather than a path variable because
     * branch names legitimately contain slashes ({@code feature/login}), which a path variable
     * cannot carry unambiguously.
     */
    @DeleteMapping("/{username}/{repoName}/branches")
    public ResponseEntity<ApiResponseEntity<Void>> deleteBranch(
            @PathVariable String username,
            @PathVariable String repoName,
            @RequestParam String name,
            Principal principal) {
        accessGuard.requireWriteAccess(username, repoName, principal);
        gitService.deleteBranch(username, repoName, name);
        return ResponseEntity.ok(ApiResponseEntity.message("Branch deleted", HttpStatus.OK));
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

    // -------------------- TRANSFER REPOSITORY --------------------
    /**
     * Moves the repository into an organization. Owner-only, like deletion; the caller must also be
     * allowed to create repositories in the target organization.
     */
    @PostMapping("/{username}/{repoName}/transfer")
    public ResponseEntity<ApiResponseEntity<TransferRepositoryResultDto>> transferRepository(
            @PathVariable String username,
            @PathVariable String repoName,
            @Valid @RequestBody TransferRepositoryDto request,
            Principal principal) {
        accessGuard.requireOwner(username, repoName, principal);
        TransferRepositoryResultDto result = transferService.transfer(username, repoName, request.newOwner(),
                currentUser.requireUsername(principal));
        return ResponseEntity.ok(ApiResponseEntity.ok(result, "Repository transferred to " + result.owner()));
    }

    // -------------------- DELETE REPOSITORY --------------------
    @DeleteMapping("/{username}/{repoName}")
    public ResponseEntity<ApiResponseEntity<Void>> deleteRepository(
            @PathVariable String username,
            @PathVariable String repoName,
            Principal principal) {

        // Deleting is owner-only — not even an ADMIN collaborator may do it.
        accessGuard.requireOwner(username, repoName, principal);
        String authUser = currentUser.requireUsername(principal);

        gitService.deleteRepository(username, repoName);
        activityService.logActivity(ActivityType.REPO_DELETED, authUser, repoName,
                "[ " + repoName + " ] Repository deleted");

        return ResponseEntity.ok(ApiResponseEntity.message("Repository deleted", HttpStatus.OK));
    }
}

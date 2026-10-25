package org.os.gitbase.git.controller;

import jakarta.validation.Valid;
import org.os.gitbase.common.ApiResponseEntity;
import org.os.gitbase.git.dto.AddIssueDependencyDto;
import org.os.gitbase.git.dto.IssueRefDto;
import org.os.gitbase.git.dto.IssueRefPageDto;
import org.os.gitbase.git.entity.enums.RepositoryPermission;
import org.os.gitbase.git.service.IssueDependencyService;
import org.os.gitbase.security.CurrentUserResolver;
import org.os.gitbase.security.RepositoryAccessGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

import static org.os.gitbase.constant.Constant.GITBASE_MAPPING_REQUEST;

/**
 * Issue dependencies — {@code /issues/{index}/dependencies} and {@code /blocks}.
 *
 * <p>A dependency is removed with {@code DELETE .../dependencies/{dependency}} — the number is in
 * the path rather than a request body, because many HTTP clients and proxies drop DELETE bodies.
 *
 * <p>Reading follows repository visibility; changing requires write access. (Unlike
 * editing an issue, authorship is not enough — otherwise any reader of a public repository could
 * attach their issue to every maintainer issue.)
 */
@RestController
@RequestMapping(GITBASE_MAPPING_REQUEST + "/{username}/{repoName}/issues/{number}")
public class IssueDependencyController {

    private final IssueDependencyService dependencyService;
    private final RepositoryAccessGuard accessGuard;
    private final CurrentUserResolver currentUser;

    public IssueDependencyController(IssueDependencyService dependencyService, RepositoryAccessGuard accessGuard,
                                     CurrentUserResolver currentUser) {
        this.dependencyService = dependencyService;
        this.accessGuard = accessGuard;
        this.currentUser = currentUser;
    }

    /** Issues this one depends on — it is "blocked by" them. */
    @GetMapping("/dependencies")
    public ResponseEntity<ApiResponseEntity<IssueRefPageDto>> dependencies(
            @PathVariable String username,
            @PathVariable String repoName,
            @PathVariable int number,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "30") int size,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        return ResponseEntity.ok(ApiResponseEntity.ok(
                dependencyService.dependencies(username, repoName, number, page, size), "Dependencies retrieved"));
    }

    /** Issues that depend on this one — it "blocks" them. */
    @GetMapping("/blocks")
    public ResponseEntity<ApiResponseEntity<IssueRefPageDto>> blocks(
            @PathVariable String username,
            @PathVariable String repoName,
            @PathVariable int number,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "30") int size,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        return ResponseEntity.ok(ApiResponseEntity.ok(
                dependencyService.dependents(username, repoName, number, page, size), "Blocked issues retrieved"));
    }

    @PostMapping("/dependencies")
    public ResponseEntity<ApiResponseEntity<IssueRefDto>> addDependency(
            @PathVariable String username,
            @PathVariable String repoName,
            @PathVariable int number,
            @Valid @RequestBody AddIssueDependencyDto request,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        IssueRefDto added = dependencyService.addDependency(username, repoName, number, request.number(),
                currentUser.requireUsername(principal), hasWriteAccess(username, repoName, principal));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseEntity.created(added, "Dependency added"));
    }

    @DeleteMapping("/dependencies/{dependency}")
    public ResponseEntity<ApiResponseEntity<Void>> removeDependency(
            @PathVariable String username,
            @PathVariable String repoName,
            @PathVariable int number,
            @PathVariable int dependency,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        dependencyService.removeDependency(username, repoName, number, dependency,
                currentUser.requireUsername(principal), hasWriteAccess(username, repoName, principal));
        return ResponseEntity.ok(ApiResponseEntity.message("Dependency removed", HttpStatus.OK));
    }

    /** Resolved once per request and handed to the service, which cannot see the Principal. */
    private boolean hasWriteAccess(String username, String repoName, Principal principal) {
        return accessGuard.effectivePermission(username, repoName, principal)
                .filter(permission -> permission.atLeast(RepositoryPermission.WRITE))
                .isPresent();
    }
}

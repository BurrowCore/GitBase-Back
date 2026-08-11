package org.os.gitbase.git.controller;

import jakarta.validation.Valid;
import org.os.gitbase.common.ApiResponseEntity;
import org.os.gitbase.git.dto.CollaboratorDto;
import org.os.gitbase.git.dto.SetCollaboratorDto;
import org.os.gitbase.git.service.CollaboratorService;
import org.os.gitbase.security.RepositoryAccessGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

import static org.os.gitbase.constant.Constant.GITBASE_MAPPING_REQUEST;

/**
 * Repository collaborator management.
 *
 * <p><b>Owner-only.</b> Following Gitea, even an ADMIN collaborator cannot manage the collaborator
 * list — that and repository deletion stay with the owner. Reading the list only needs read access,
 * so collaborators can see who else they are working with.
 *
 * <p>A grant takes effect immediately; there is no invitation/acceptance step (Gitea's behaviour,
 * unlike GitHub's).
 */
@RestController
@RequestMapping(GITBASE_MAPPING_REQUEST + "/{username}/{repoName}/collaborators")
public class CollaboratorController {

    private final CollaboratorService collaboratorService;
    private final RepositoryAccessGuard accessGuard;

    public CollaboratorController(CollaboratorService collaboratorService, RepositoryAccessGuard accessGuard) {
        this.collaboratorService = collaboratorService;
        this.accessGuard = accessGuard;
    }

    // -------------------- LIST --------------------
    @GetMapping
    public ResponseEntity<ApiResponseEntity<List<CollaboratorDto>>> list(
            @PathVariable String username,
            @PathVariable String repoName,
            Principal principal) {
        accessGuard.requireReadAccess(username, repoName, principal);
        List<CollaboratorDto> collaborators = collaboratorService.list(username, repoName);
        return ResponseEntity.ok(ApiResponseEntity.ok(collaborators, "Collaborators retrieved"));
    }

    // -------------------- ADD / UPDATE --------------------

    /** Idempotent: adds the collaborator, or updates their permission if they already have one. */
    @PutMapping("/{collaborator}")
    public ResponseEntity<ApiResponseEntity<CollaboratorDto>> set(
            @PathVariable String username,
            @PathVariable String repoName,
            @PathVariable String collaborator,
            @Valid @RequestBody SetCollaboratorDto request,
            Principal principal) {
        accessGuard.requireOwner(username, repoName, principal);
        CollaboratorDto saved = collaboratorService.set(username, repoName, collaborator, request.permission());
        return ResponseEntity.ok(ApiResponseEntity.ok(saved, "Collaborator saved"));
    }

    // -------------------- REMOVE --------------------
    @DeleteMapping("/{collaborator}")
    public ResponseEntity<ApiResponseEntity<Void>> remove(
            @PathVariable String username,
            @PathVariable String repoName,
            @PathVariable String collaborator,
            Principal principal) {
        accessGuard.requireOwner(username, repoName, principal);
        collaboratorService.remove(username, repoName, collaborator);
        return ResponseEntity.ok(ApiResponseEntity.message("Collaborator removed", HttpStatus.OK));
    }
}

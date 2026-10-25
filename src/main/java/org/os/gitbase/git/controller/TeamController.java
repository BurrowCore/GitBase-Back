package org.os.gitbase.git.controller;

import jakarta.validation.Valid;
import org.os.gitbase.common.ApiResponseEntity;
import org.os.gitbase.git.dto.OrganizationDtos.CreateTeamDto;
import org.os.gitbase.git.dto.OrganizationDtos.PageDto;
import org.os.gitbase.git.dto.OrganizationDtos.TeamDto;
import org.os.gitbase.git.dto.OrganizationDtos.TeamRepositoryDto;
import org.os.gitbase.git.dto.OrganizationDtos.UpdateTeamDto;
import org.os.gitbase.git.dto.UserSummaryDto;
import org.os.gitbase.git.service.TeamService;
import org.os.gitbase.security.OrganizationAccessGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

import static org.os.gitbase.constant.Constant.GITBASE_MAPPING_REQUEST;

/**
 * Teams of an organization. Gitbase addresses teams as {@code /teams/{id}}; here they are nested
 * under their organization by name ({@code /orgs/{org}/teams/{team}}) so URLs read naturally and a
 * team id from one organization can never be used against another.
 *
 * <p>Members may read; owners may change anything. Adding a member or a repository is an
 * idempotent {@code PUT}.
 */
@RestController
@RequestMapping(GITBASE_MAPPING_REQUEST + "/orgs/{org}/teams")
public class TeamController {

    private final TeamService teamService;
    private final OrganizationAccessGuard orgGuard;

    public TeamController(TeamService teamService, OrganizationAccessGuard orgGuard) {
        this.teamService = teamService;
        this.orgGuard = orgGuard;
    }

    @GetMapping
    public ResponseEntity<ApiResponseEntity<PageDto<TeamDto>>> list(
            @PathVariable String org,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "30") int size,
            Principal principal) {
        orgGuard.requireMember(org, principal);
        return ResponseEntity.ok(ApiResponseEntity.ok(teamService.list(org, page, size), "Teams retrieved"));
    }

    @PostMapping
    public ResponseEntity<ApiResponseEntity<TeamDto>> create(
            @PathVariable String org,
            @Valid @RequestBody CreateTeamDto request,
            Principal principal) {
        orgGuard.requireOwner(org, principal);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseEntity.created(teamService.create(org, request), "Team created"));
    }

    @GetMapping("/{team}")
    public ResponseEntity<ApiResponseEntity<TeamDto>> get(
            @PathVariable String org, @PathVariable String team, Principal principal) {
        orgGuard.requireMember(org, principal);
        return ResponseEntity.ok(ApiResponseEntity.ok(teamService.get(org, team), "Team retrieved"));
    }

    @PatchMapping("/{team}")
    public ResponseEntity<ApiResponseEntity<TeamDto>> update(
            @PathVariable String org, @PathVariable String team,
            @Valid @RequestBody UpdateTeamDto request, Principal principal) {
        orgGuard.requireOwner(org, principal);
        return ResponseEntity.ok(ApiResponseEntity.ok(teamService.update(org, team, request), "Team updated"));
    }

    @DeleteMapping("/{team}")
    public ResponseEntity<ApiResponseEntity<Void>> delete(
            @PathVariable String org, @PathVariable String team, Principal principal) {
        orgGuard.requireOwner(org, principal);
        teamService.delete(org, team);
        return ResponseEntity.ok(ApiResponseEntity.message("Team deleted", HttpStatus.OK));
    }

    // ───────────────────────── members ─────────────────────────

    @GetMapping("/{team}/members")
    public ResponseEntity<ApiResponseEntity<PageDto<UserSummaryDto>>> members(
            @PathVariable String org, @PathVariable String team,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "30") int size,
            Principal principal) {
        orgGuard.requireMember(org, principal);
        return ResponseEntity.ok(ApiResponseEntity.ok(teamService.members(org, team, page, size), "Team members retrieved"));
    }

    @PutMapping("/{team}/members/{username}")
    public ResponseEntity<ApiResponseEntity<Void>> addMember(
            @PathVariable String org, @PathVariable String team, @PathVariable String username,
            Principal principal) {
        orgGuard.requireOwner(org, principal);
        teamService.addMember(org, team, username);
        return ResponseEntity.ok(ApiResponseEntity.message("Member added", HttpStatus.OK));
    }

    @DeleteMapping("/{team}/members/{username}")
    public ResponseEntity<ApiResponseEntity<Void>> removeMember(
            @PathVariable String org, @PathVariable String team, @PathVariable String username,
            Principal principal) {
        orgGuard.requireOwner(org, principal);
        teamService.removeMember(org, team, username);
        return ResponseEntity.ok(ApiResponseEntity.message("Member removed", HttpStatus.OK));
    }

    // ───────────────────────── repositories ─────────────────────────

    @GetMapping("/{team}/repos")
    public ResponseEntity<ApiResponseEntity<PageDto<TeamRepositoryDto>>> repositories(
            @PathVariable String org, @PathVariable String team,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "30") int size,
            Principal principal) {
        orgGuard.requireMember(org, principal);
        return ResponseEntity.ok(ApiResponseEntity.ok(teamService.repositories(org, team, page, size),
                "Team repositories retrieved"));
    }

    @PutMapping("/{team}/repos/{repo}")
    public ResponseEntity<ApiResponseEntity<Void>> addRepository(
            @PathVariable String org, @PathVariable String team, @PathVariable String repo,
            Principal principal) {
        orgGuard.requireOwner(org, principal);
        teamService.addRepository(org, team, repo);
        return ResponseEntity.ok(ApiResponseEntity.message("Repository added to team", HttpStatus.OK));
    }

    @DeleteMapping("/{team}/repos/{repo}")
    public ResponseEntity<ApiResponseEntity<Void>> removeRepository(
            @PathVariable String org, @PathVariable String team, @PathVariable String repo,
            Principal principal) {
        orgGuard.requireOwner(org, principal);
        teamService.removeRepository(org, team, repo);
        return ResponseEntity.ok(ApiResponseEntity.message("Repository removed from team", HttpStatus.OK));
    }
}

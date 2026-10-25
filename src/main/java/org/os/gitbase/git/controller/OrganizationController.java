package org.os.gitbase.git.controller;

import jakarta.validation.Valid;
import org.os.gitbase.common.ApiResponseEntity;
import org.os.gitbase.git.dto.OrganizationDtos.CreateOrganizationDto;
import org.os.gitbase.git.dto.OrganizationDtos.OrganizationDto;
import org.os.gitbase.git.dto.OrganizationDtos.OrganizationSummaryDto;
import org.os.gitbase.git.dto.OrganizationDtos.PageDto;
import org.os.gitbase.git.dto.OrganizationDtos.UpdateOrganizationDto;
import org.os.gitbase.git.dto.UserSummaryDto;
import org.os.gitbase.git.service.OrganizationService;
import org.os.gitbase.security.CurrentUserResolver;
import org.os.gitbase.security.OrganizationAccessGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

import static org.os.gitbase.constant.Constant.GITBASE_MAPPING_REQUEST;

/**
 * Organizations — shaped API. An organization's repositories are
 * served by the ordinary repository endpoints ({@code /{org}/{repo}/...}), because an organization
 * shares the user namespace.
 *
 * <p>Authorization: any signed-in user may create an organization and see its profile; members
 * may list members; owners may edit, delete and remove members.
 */
@RestController
@RequestMapping(GITBASE_MAPPING_REQUEST)
public class OrganizationController {

    private final OrganizationService organizationService;
    private final OrganizationAccessGuard orgGuard;
    private final CurrentUserResolver currentUser;

    public OrganizationController(OrganizationService organizationService, OrganizationAccessGuard orgGuard,
                                  CurrentUserResolver currentUser) {
        this.organizationService = organizationService;
        this.orgGuard = orgGuard;
        this.currentUser = currentUser;
    }

    @PostMapping("/orgs")
    public ResponseEntity<ApiResponseEntity<OrganizationDto>> create(
            @Valid @RequestBody CreateOrganizationDto request,
            Principal principal) {
        OrganizationDto created = organizationService.create(currentUser.requireUsername(principal), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseEntity.created(created, "Organization created"));
    }

    @GetMapping("/orgs/{org}")
    public ResponseEntity<ApiResponseEntity<OrganizationDto>> get(@PathVariable String org, Principal principal) {
        orgGuard.requireOrganization(org);
        return ResponseEntity.ok(ApiResponseEntity.ok(
                organizationService.get(org, currentUser.usernameOrNull(principal)), "Organization retrieved"));
    }

    @PatchMapping("/orgs/{org}")
    public ResponseEntity<ApiResponseEntity<OrganizationDto>> update(
            @PathVariable String org,
            @Valid @RequestBody UpdateOrganizationDto request,
            Principal principal) {
        orgGuard.requireOwner(org, principal);
        return ResponseEntity.ok(ApiResponseEntity.ok(
                organizationService.update(org, currentUser.requireUsername(principal), request), "Organization updated"));
    }

    @DeleteMapping("/orgs/{org}")
    public ResponseEntity<ApiResponseEntity<Void>> delete(@PathVariable String org, Principal principal) {
        orgGuard.requireOwner(org, principal);
        organizationService.delete(org);
        return ResponseEntity.ok(ApiResponseEntity.message("Organization deleted", HttpStatus.OK));
    }

    @GetMapping("/orgs/{org}/members")
    public ResponseEntity<ApiResponseEntity<PageDto<UserSummaryDto>>> members(
            @PathVariable String org,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "30") int size,
            Principal principal) {
        orgGuard.requireMember(org, principal);
        return ResponseEntity.ok(ApiResponseEntity.ok(organizationService.members(org, page, size), "Members retrieved"));
    }

    /** Removes the user from every team of the organization. Owners only; never the last owner. */
    @DeleteMapping("/orgs/{org}/members/{username}")
    public ResponseEntity<ApiResponseEntity<Void>> removeMember(
            @PathVariable String org,
            @PathVariable String username,
            Principal principal) {
        orgGuard.requireOwner(org, principal);
        organizationService.removeMember(org, username);
        return ResponseEntity.ok(ApiResponseEntity.message("Member removed", HttpStatus.OK));
    }

    /** Organizations the caller belongs to ({@code GET /user/orgs}). */
    @GetMapping("/user/orgs")
    public ResponseEntity<ApiResponseEntity<PageDto<OrganizationSummaryDto>>> myOrganizations(
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "30") int size,
            Principal principal) {
        return ResponseEntity.ok(ApiResponseEntity.ok(
                organizationService.organizationsOf(currentUser.requireUsername(principal), page, size),
                "Organizations retrieved"));
    }
}

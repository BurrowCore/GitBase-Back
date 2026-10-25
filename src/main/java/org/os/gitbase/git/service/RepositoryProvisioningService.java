package org.os.gitbase.git.service;

import org.os.gitbase.exception.AccessDeniedDomainException;
import org.os.gitbase.git.repository.TeamMemberRepository;
import org.springframework.stereotype.Service;

/**
 * Decides where a new repository may be created, then creates it.
 *
 * <p>The owner is the caller, or an organization they may create repositories in (an owner, or a
 * member of a team with {@code canCreateOrgRepo}). A creator who is not an owner of
 * the organization is made an ADMIN collaborator of the new repository, so they are not locked
 * out of what they just created when their team does not cover it.
 */
@Service
public class RepositoryProvisioningService {

    private final GitService gitService;
    private final OrganizationService organizationService;
    private final CollaboratorService collaboratorService;
    private final TeamMemberRepository memberRepository;

    public RepositoryProvisioningService(GitService gitService, OrganizationService organizationService,
                                         CollaboratorService collaboratorService, TeamMemberRepository memberRepository) {
        this.gitService = gitService;
        this.organizationService = organizationService;
        this.collaboratorService = collaboratorService;
        this.memberRepository = memberRepository;
    }

    /**
     * @param requestedOwner the namespace asked for, or null/blank for the caller's own
     * @return the owner the repository was created under
     * @throws AccessDeniedDomainException if the caller may not create repositories there (403)
     */
    public String create(String caller, String requestedOwner, String repoName, boolean isPrivate,
                         String defaultBranch) {
        String owner = requestedOwner == null || requestedOwner.isBlank() ? caller : requestedOwner.trim();
        if (owner.equals(caller)) {
            gitService.createRepository(caller, repoName, isPrivate, defaultBranch);
            return caller;
        }
        if (!organizationService.canCreateRepository(owner, caller)) {
            throw new AccessDeniedDomainException(
                    "You can only create repositories under your own account or an organization that allows you to");
        }
        gitService.createRepository(owner, repoName, isPrivate, defaultBranch);
        if (!memberRepository.isOwner(owner, caller)) {
            collaboratorService.set(owner, repoName, caller, "ADMIN");
        }
        return owner;
    }
}

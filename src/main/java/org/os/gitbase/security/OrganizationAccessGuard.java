package org.os.gitbase.security;

import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.exception.AccessDeniedDomainException;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.git.repository.TeamMemberRepository;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * Organization-level authorization, the counterpart of {@link RepositoryAccessGuard}.
 *
 * <ul>
 *   <li><b>Anyone signed in</b> may see an organization's profile and its public repositories
 *       (all organizations are public for now).</li>
 *   <li><b>Members</b> — anyone in one of its teams — may see its members and teams.</li>
 *   <li><b>Owners</b> — members of its Owners team — may change it.</li>
 * </ul>
 *
 * <p>A name that is a user rather than an organization is "not found", so organization endpoints
 * cannot be pointed at a personal account.
 */
@Component
public class OrganizationAccessGuard {

    private final UserRepository userRepository;
    private final TeamMemberRepository memberRepository;
    private final CurrentUserResolver currentUser;

    public OrganizationAccessGuard(UserRepository userRepository, TeamMemberRepository memberRepository,
                                   CurrentUserResolver currentUser) {
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.currentUser = currentUser;
    }

    public User requireOrganization(String orgName) {
        return userRepository.findUserByName(orgName)
                .filter(User::isOrganization)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgName));
    }

    /** @return the organization, after checking the caller belongs to it */
    public User requireMember(String orgName, Principal principal) {
        User org = requireOrganization(orgName);
        if (!memberRepository.isMember(orgName, currentUser.requireUsername(principal))) {
            throw new AccessDeniedDomainException("Only members of " + orgName + " can see this");
        }
        return org;
    }

    /** @return the organization, after checking the caller owns it */
    public User requireOwner(String orgName, Principal principal) {
        User org = requireOrganization(orgName);
        if (!memberRepository.isOwner(orgName, currentUser.requireUsername(principal))) {
            throw new AccessDeniedDomainException("Only owners of " + orgName + " can do this");
        }
        return org;
    }
}

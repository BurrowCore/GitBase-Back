package org.os.gitbase.git.service;

import org.os.gitbase.git.dto.OrganizationDtos.CreateOrganizationDto;
import org.os.gitbase.git.dto.OrganizationDtos.OrganizationDto;
import org.os.gitbase.git.dto.OrganizationDtos.OrganizationSummaryDto;
import org.os.gitbase.git.dto.OrganizationDtos.PageDto;
import org.os.gitbase.git.dto.OrganizationDtos.UpdateOrganizationDto;
import org.os.gitbase.git.dto.UserSummaryDto;

/**
 * Organizations: shared namespaces that own repositories, with access managed through teams.
 * An organization is a {@code t_users} row of type ORGANIZATION, so it shares the user namespace.
 *
 * <p>Authorization (member/owner) is checked by the controller through
 * {@code OrganizationAccessGuard}; rules that depend on organization state (the last owner cannot
 * leave, an organization with repositories cannot be deleted) live here.
 */
public interface OrganizationService {

    /** Creates the organization and its Owners team, with {@code creator} as the first owner. */
    OrganizationDto create(String creator, CreateOrganizationDto request);

    OrganizationDto get(String orgName, String viewer);

    OrganizationDto update(String orgName, String viewer, UpdateOrganizationDto request);

    /** Refused (409) while the organization still owns repositories — delete them first. */
    void delete(String orgName);

    /** Organizations {@code username} belongs to. */
    PageDto<OrganizationSummaryDto> organizationsOf(String username, int page, int size);

    PageDto<UserSummaryDto> members(String orgName, int page, int size);

    /** Removes {@code username} from every team of the organization; refused for its last owner. */
    void removeMember(String orgName, String username);

    /** Owners, and members of a team with {@code canCreateOrgRepo}, may create repositories there. */
    boolean canCreateRepository(String orgName, String username);
}

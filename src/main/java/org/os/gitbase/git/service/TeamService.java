package org.os.gitbase.git.service;

import org.os.gitbase.git.dto.OrganizationDtos.CreateTeamDto;
import org.os.gitbase.git.dto.OrganizationDtos.PageDto;
import org.os.gitbase.git.dto.OrganizationDtos.TeamDto;
import org.os.gitbase.git.dto.OrganizationDtos.TeamRepositoryDto;
import org.os.gitbase.git.dto.OrganizationDtos.UpdateTeamDto;
import org.os.gitbase.git.dto.UserSummaryDto;

/**
 * Teams of an organization: a permission level, applied to chosen repositories or
 * to all of them, plus whether members may create repositories. The Owners team is special: it
 * cannot be renamed, re-permissioned or deleted, and must keep at least one member.
 *
 * <p>Teams are addressed by name within their organization, not by a global id. Names are
 * unique per organization ignoring case, and limited to URL-safe characters.
 */
public interface TeamService {

    PageDto<TeamDto> list(String orgName, int page, int size);

    TeamDto get(String orgName, String teamName);

    TeamDto create(String orgName, CreateTeamDto request);

    TeamDto update(String orgName, String teamName, UpdateTeamDto request);

    void delete(String orgName, String teamName);

    PageDto<UserSummaryDto> members(String orgName, String teamName, int page, int size);

    /** Adding a user to any team makes them an organization member — there is no invitation step. */
    void addMember(String orgName, String teamName, String username);

    void removeMember(String orgName, String teamName, String username);

    /** The team's repositories — every organization repository when it includes all of them. */
    PageDto<TeamRepositoryDto> repositories(String orgName, String teamName, int page, int size);

    void addRepository(String orgName, String teamName, String repoName);

    void removeRepository(String orgName, String teamName, String repoName);
}

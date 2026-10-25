package org.os.gitbase.git.service;

import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.exception.ConflictException;
import org.os.gitbase.exception.InvalidStateException;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.git.dto.OrganizationDtos.CreateTeamDto;
import org.os.gitbase.git.dto.OrganizationDtos.PageDto;
import org.os.gitbase.git.dto.OrganizationDtos.TeamDto;
import org.os.gitbase.git.dto.OrganizationDtos.TeamRepositoryDto;
import org.os.gitbase.git.dto.OrganizationDtos.UpdateTeamDto;
import org.os.gitbase.git.dto.UserSummaryDto;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.entity.Team;
import org.os.gitbase.git.entity.TeamMember;
import org.os.gitbase.git.entity.TeamRepo;
import org.os.gitbase.git.entity.enums.RepositoryPermission;
import org.os.gitbase.git.mapper.UserSummaryMapper;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.os.gitbase.git.repository.TeamMemberRepository;
import org.os.gitbase.git.repository.TeamRepoRepository;
import org.os.gitbase.git.repository.TeamRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class TeamServiceImpl implements TeamService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 30;

    private final TeamRepository teamRepository;
    private final TeamMemberRepository memberRepository;
    private final TeamRepoRepository teamRepoRepository;
    private final UserRepository userRepository;
    private final GitRepositoryDB repositoryDB;
    private final UserSummaryMapper userSummaryMapper;

    public TeamServiceImpl(TeamRepository teamRepository, TeamMemberRepository memberRepository,
                           TeamRepoRepository teamRepoRepository, UserRepository userRepository,
                           GitRepositoryDB repositoryDB, UserSummaryMapper userSummaryMapper) {
        this.teamRepository = teamRepository;
        this.memberRepository = memberRepository;
        this.teamRepoRepository = teamRepoRepository;
        this.userRepository = userRepository;
        this.repositoryDB = repositoryDB;
        this.userSummaryMapper = userSummaryMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public PageDto<TeamDto> list(String orgName, int page, int size) {
        Page<Team> teams = teamRepository.findByOrganization(requireOrganization(orgName), pageRequest(page, size));
        return new PageDto<>(teams.getContent().stream().map(this::toDto).toList(), teams.getNumber(),
                teams.getSize(), teams.getTotalElements(), teams.hasNext());
    }

    @Override
    @Transactional(readOnly = true)
    public TeamDto get(String orgName, String teamName) {
        return toDto(requireTeam(orgName, teamName));
    }

    @Override
    @Transactional
    public TeamDto create(String orgName, CreateTeamDto request) {
        User org = requireOrganization(orgName);
        String name = request.name().trim();
        if (teamRepository.existsByOrganizationAndLowerName(org, name.toLowerCase(Locale.ROOT))) {
            throw new ConflictException("A team named '" + name + "' already exists in " + orgName);
        }
        Team team = new Team();
        team.setOrganization(org);
        team.setName(name);
        team.setDescription(request.description());
        team.setPermission(parsePermission(request.permission()));
        team.setIncludesAllRepositories(request.includesAllRepositories());
        team.setCanCreateOrgRepo(request.canCreateOrgRepo());
        return toDto(teamRepository.save(team));
    }

    @Override
    @Transactional
    public TeamDto update(String orgName, String teamName, UpdateTeamDto request) {
        Team team = requireTeam(orgName, teamName);
        boolean structural = request.name() != null || request.permission() != null
                || request.includesAllRepositories() != null || request.canCreateOrgRepo() != null;
        if (team.isOwnerTeam() && structural) {
            // The Owners team defines who owns the organization, so its shape is fixed.
            throw new InvalidStateException("The Owners team can only have its description changed");
        }
        if (request.name() != null && !request.name().trim().equalsIgnoreCase(team.getName())) {
            String name = request.name().trim();
            if (teamRepository.existsByOrganizationAndLowerName(team.getOrganization(), name.toLowerCase(Locale.ROOT))) {
                throw new ConflictException("A team named '" + name + "' already exists in " + orgName);
            }
            team.setName(name);
        } else if (request.name() != null) {
            team.setName(request.name().trim()); // case-only rename
        }
        if (request.description() != null) {
            team.setDescription(request.description());
        }
        if (request.permission() != null) {
            team.setPermission(parsePermission(request.permission()));
        }
        if (request.includesAllRepositories() != null) {
            team.setIncludesAllRepositories(request.includesAllRepositories());
        }
        if (request.canCreateOrgRepo() != null) {
            team.setCanCreateOrgRepo(request.canCreateOrgRepo());
        }
        return toDto(teamRepository.save(team));
    }

    @Override
    @Transactional
    public void delete(String orgName, String teamName) {
        Team team = requireTeam(orgName, teamName);
        if (team.isOwnerTeam()) {
            throw new InvalidStateException("The Owners team cannot be deleted");
        }
        teamRepoRepository.deleteByTeam(team);
        memberRepository.deleteByTeam(team);
        teamRepository.delete(team);
    }

    @Override
    @Transactional(readOnly = true)
    public PageDto<UserSummaryDto> members(String orgName, String teamName, int page, int size) {
        Page<User> users = memberRepository.findUsersOfTeam(requireTeam(orgName, teamName), pageRequest(page, size));
        return new PageDto<>(userSummaryMapper.toSummaryList(users.getContent()), users.getNumber(), users.getSize(),
                users.getTotalElements(), users.hasNext());
    }

    @Override
    @Transactional
    public void addMember(String orgName, String teamName, String username) {
        Team team = requireTeam(orgName, teamName);
        User user = userRepository.findUserByName(username == null ? "" : username.trim())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
        if (user.isOrganization()) {
            throw new IllegalArgumentException(username + " is an organization, not a user");
        }
        if (memberRepository.existsByTeamAndUser(team, user)) {
            return; // idempotent PUT
        }
        TeamMember member = new TeamMember();
        member.setTeam(team);
        member.setUser(user);
        memberRepository.save(member);
    }

    @Override
    @Transactional
    public void removeMember(String orgName, String teamName, String username) {
        Team team = requireTeam(orgName, teamName);
        User user = userRepository.findUserByName(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
        TeamMember member = memberRepository.findByTeamAndUser(team, user)
                .orElseThrow(() -> new ResourceNotFoundException(username + " is not in team " + teamName));
        if (team.isOwnerTeam() && memberRepository.countByTeam(team) <= 1) {
            throw new ConflictException(username + " is the last owner of " + orgName + " — add another owner first");
        }
        memberRepository.delete(member);
    }

    @Override
    @Transactional(readOnly = true)
    public PageDto<TeamRepositoryDto> repositories(String orgName, String teamName, int page, int size) {
        Team team = requireTeam(orgName, teamName);
        Page<RepositoryGit> repos = team.isIncludesAllRepositories() || team.isOwnerTeam()
                ? repositoryDB.findByOwnerOrderByRepoNameAsc(team.getOrganization(), pageRequest(page, size))
                : teamRepoRepository.findRepositoriesOfTeam(team, pageRequest(page, size));
        return new PageDto<>(repos.getContent().stream()
                .map(r -> new TeamRepositoryDto(r.getRepoName(), r.isPrivate() ? "private" : "public"))
                .toList(), repos.getNumber(), repos.getSize(), repos.getTotalElements(), repos.hasNext());
    }

    @Override
    @Transactional
    public void addRepository(String orgName, String teamName, String repoName) {
        Team team = requireTeam(orgName, teamName);
        if (team.isIncludesAllRepositories() || team.isOwnerTeam()) {
            throw new InvalidStateException("Team " + teamName + " already includes every repository");
        }
        RepositoryGit repo = requireOrgRepository(orgName, repoName);
        if (teamRepoRepository.existsByTeamAndRepository(team, repo)) {
            return; // idempotent
        }
        TeamRepo grant = new TeamRepo();
        grant.setTeam(team);
        grant.setRepository(repo);
        teamRepoRepository.save(grant);
    }

    @Override
    @Transactional
    public void removeRepository(String orgName, String teamName, String repoName) {
        Team team = requireTeam(orgName, teamName);
        RepositoryGit repo = requireOrgRepository(orgName, repoName);
        TeamRepo grant = teamRepoRepository.findByTeamAndRepository(team, repo)
                .orElseThrow(() -> new ResourceNotFoundException(repoName + " is not granted to team " + teamName));
        teamRepoRepository.delete(grant);
    }

    // ───────────────────────── helpers ─────────────────────────

    private TeamDto toDto(Team team) {
        long repositories = team.isIncludesAllRepositories() || team.isOwnerTeam()
                ? repositoryDB.countByOwner(team.getOrganization())
                : teamRepoRepository.countByTeam(team);
        return new TeamDto(team.getName(), team.getDescription(), team.getPermission().name(),
                team.isIncludesAllRepositories(), team.isCanCreateOrgRepo(), team.isOwnerTeam(),
                memberRepository.countByTeam(team), repositories);
    }

    private User requireOrganization(String orgName) {
        return userRepository.findUserByName(orgName)
                .filter(User::isOrganization)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgName));
    }

    private Team requireTeam(String orgName, String teamName) {
        return teamRepository.findByOrganizationAndLowerName(requireOrganization(orgName),
                        teamName == null ? "" : teamName.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException("Team not found: " + orgName + "/" + teamName));
    }

    /** Only the organization's own repositories can be granted to its teams. */
    private RepositoryGit requireOrgRepository(String orgName, String repoName) {
        return repositoryDB.findByOwnerNameAndRepoName(orgName, repoName)
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found: " + orgName + "/" + repoName));
    }

    private static RepositoryPermission parsePermission(String permission) {
        return permission == null ? RepositoryPermission.READ : RepositoryPermission.valueOf(permission);
    }

    private static PageRequest pageRequest(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size <= 0 ? DEFAULT_PAGE_SIZE : size, 1, MAX_PAGE_SIZE));
    }
}

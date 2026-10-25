package org.os.gitbase.git.service;

import lombok.extern.slf4j.Slf4j;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.entity.enums.AccountType;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.auth.service.UsernameAllocator;
import org.os.gitbase.exception.ConflictException;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.git.dto.OrganizationDtos.CreateOrganizationDto;
import org.os.gitbase.git.dto.OrganizationDtos.OrganizationDto;
import org.os.gitbase.git.dto.OrganizationDtos.OrganizationSummaryDto;
import org.os.gitbase.git.dto.OrganizationDtos.PageDto;
import org.os.gitbase.git.dto.OrganizationDtos.UpdateOrganizationDto;
import org.os.gitbase.git.dto.UserSummaryDto;
import org.os.gitbase.git.entity.Team;
import org.os.gitbase.git.entity.TeamMember;
import org.os.gitbase.git.entity.enums.RepositoryPermission;
import org.os.gitbase.git.mapper.TemporalMapper;
import org.os.gitbase.git.mapper.UserSummaryMapper;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.os.gitbase.git.repository.TeamMemberRepository;
import org.os.gitbase.git.repository.TeamRepoRepository;
import org.os.gitbase.git.repository.TeamRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
public class OrganizationServiceImpl implements OrganizationService {

    static final String OWNERS_TEAM = "Owners";
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 30;

    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository memberRepository;
    private final TeamRepoRepository teamRepoRepository;
    private final GitRepositoryDB repositoryDB;
    private final UsernameAllocator usernameAllocator;
    private final UserSummaryMapper userSummaryMapper;
    private final TemporalMapper temporalMapper;

    public OrganizationServiceImpl(UserRepository userRepository, TeamRepository teamRepository,
                                   TeamMemberRepository memberRepository, TeamRepoRepository teamRepoRepository,
                                   GitRepositoryDB repositoryDB, UsernameAllocator usernameAllocator,
                                   UserSummaryMapper userSummaryMapper, TemporalMapper temporalMapper) {
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
        this.memberRepository = memberRepository;
        this.teamRepoRepository = teamRepoRepository;
        this.repositoryDB = repositoryDB;
        this.usernameAllocator = usernameAllocator;
        this.userSummaryMapper = userSummaryMapper;
        this.temporalMapper = temporalMapper;
    }

    @Override
    @Transactional
    public OrganizationDto create(String creator, CreateOrganizationDto request) {
        // Stored lowercase: names are paths, and the namespace is case-insensitive.
        String name = request.name().trim().toLowerCase(Locale.ROOT);
        if (!usernameAllocator.isAvailable(name)) {
            throw new ConflictException("The name '" + name + "' is already taken");
        }
        User owner = requireUser(creator);

        User org = new User();
        org.setName(name);
        org.setFullName(blankToNull(request.fullName()));
        org.setDescription(blankToNull(request.description()));
        org.setAccountType(AccountType.ORGANIZATION);
        // Organizations never sign in: no password, disabled, and a unique address on a reserved
        // TLD (RFC 2606) that no mailbox or OAuth provider can ever produce.
        org.setEmail("org-" + UUID.randomUUID() + "@organizations.gitbase.invalid");
        org.setEnabled(false);
        org.setCreatedAt(LocalDateTime.now());
        userRepository.save(org);

        Team owners = new Team();
        owners.setOrganization(org);
        owners.setName(OWNERS_TEAM);
        owners.setDescription("Owners of " + name + " — full access to everything");
        owners.setPermission(RepositoryPermission.ADMIN);
        owners.setOwnerTeam(true);
        owners.setIncludesAllRepositories(true);
        owners.setCanCreateOrgRepo(true);
        teamRepository.save(owners);

        TeamMember membership = new TeamMember();
        membership.setTeam(owners);
        membership.setUser(owner);
        memberRepository.save(membership);

        log.info("Organization {} created by {}", name, creator);
        return toDto(org, creator);
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizationDto get(String orgName, String viewer) {
        return toDto(requireOrganization(orgName), viewer);
    }

    @Override
    @Transactional
    public OrganizationDto update(String orgName, String viewer, UpdateOrganizationDto request) {
        User org = requireOrganization(orgName);
        if (request.fullName() != null) {
            org.setFullName(blankToNull(request.fullName()));
        }
        if (request.description() != null) {
            org.setDescription(blankToNull(request.description()));
        }
        return toDto(userRepository.save(org), viewer);
    }

    @Override
    @Transactional
    public void delete(String orgName) {
        User org = requireOrganization(orgName);
        long repositories = repositoryDB.countByOwner(org);
        if (repositories > 0) {
            throw new ConflictException(orgName + " still owns " + repositories
                    + " repositor" + (repositories == 1 ? "y" : "ies") + " — delete or transfer them first");
        }
        teamRepoRepository.deleteByOrganization(org);
        memberRepository.deleteByOrganization(org);
        teamRepository.deleteByOrganization(org);
        userRepository.delete(org);
        log.info("Organization {} deleted", orgName);
    }

    @Override
    @Transactional(readOnly = true)
    public PageDto<OrganizationSummaryDto> organizationsOf(String username, int page, int size) {
        User user = requireUser(username);
        Page<User> orgs = memberRepository.findOrganizationsOf(user, pageRequest(page, size));
        List<OrganizationSummaryDto> rows = orgs.getContent().stream()
                .map(org -> new OrganizationSummaryDto(org.getName(), org.getFullName(), org.getDescription(),
                        memberRepository.isOwner(org.getName(), username),
                        canCreateRepository(org.getName(), username)))
                .toList();
        return new PageDto<>(rows, orgs.getNumber(), orgs.getSize(), orgs.getTotalElements(), orgs.hasNext());
    }

    @Override
    @Transactional(readOnly = true)
    public PageDto<UserSummaryDto> members(String orgName, int page, int size) {
        Page<User> members = memberRepository.findMembersOfOrganization(requireOrganization(orgName),
                pageRequest(page, size));
        return new PageDto<>(userSummaryMapper.toSummaryList(members.getContent()), members.getNumber(),
                members.getSize(), members.getTotalElements(), members.hasNext());
    }

    @Override
    @Transactional
    public void removeMember(String orgName, String username) {
        User org = requireOrganization(orgName);
        User member = requireUser(username);
        Team owners = teamRepository.findByOrganizationAndOwnerTeamTrue(org)
                .orElseThrow(() -> new IllegalStateException("Organization " + orgName + " has no Owners team"));
        if (memberRepository.existsByTeamAndUser(owners, member) && memberRepository.countByTeam(owners) <= 1) {
            throw new ConflictException(username + " is the last owner of " + orgName
                    + " — add another owner first, or delete the organization");
        }
        memberRepository.deleteFromOrganization(org, member);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canCreateRepository(String orgName, String username) {
        if (username == null) {
            return false;
        }
        return teamRepository.findTeamsOfMember(orgName, username).stream()
                .anyMatch(team -> team.isOwnerTeam() || team.isCanCreateOrgRepo());
    }

    // ───────────────────────── helpers ─────────────────────────

    private OrganizationDto toDto(User org, String viewer) {
        boolean member = viewer != null && memberRepository.isMember(org.getName(), viewer);
        boolean owner = member && memberRepository.isOwner(org.getName(), viewer);
        return new OrganizationDto(org.getName(), org.getFullName(), org.getDescription(),
                temporalMapper.toEpochMillis(org.getCreatedAt()), repositoryDB.countByOwner(org),
                member, owner, member && canCreateRepository(org.getName(), viewer));
    }

    private User requireOrganization(String orgName) {
        return userRepository.findUserByName(orgName)
                .filter(User::isOrganization)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgName));
    }

    private User requireUser(String username) {
        return userRepository.findUserByName(username)
                .filter(user -> !user.isOrganization())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
    }

    private static PageRequest pageRequest(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size <= 0 ? DEFAULT_PAGE_SIZE : size, 1, MAX_PAGE_SIZE));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

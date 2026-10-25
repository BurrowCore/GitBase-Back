package org.os.gitbase.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.exception.AccessDeniedDomainException;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.entity.Collaborator;
import org.os.gitbase.git.entity.enums.RepositoryPermission;
import org.os.gitbase.git.repository.CollaboratorRepository;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.os.gitbase.git.repository.TeamGrant;
import org.os.gitbase.git.repository.TeamMemberRepository;
import org.os.gitbase.git.repository.TeamRepoRepository;
import org.os.gitbase.git.repository.TeamRepository;

import java.security.Principal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Verifies the repository visibility gate that protects every read/write door
 * (REST endpoints, the JGit servlet, and the smart-HTTP command controller).
 */
@ExtendWith(MockitoExtension.class)
class RepositoryAccessGuardTest {

    @Mock
    private GitRepositoryDB repositoryDB;

    @Mock
    private CollaboratorRepository collaboratorRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    @Mock
    private TeamRepoRepository teamRepoRepository;

    private RepositoryAccessGuard guard;

    @BeforeEach
    void setUp() {
        guard = new RepositoryAccessGuard(repositoryDB, collaboratorRepository,
                new CurrentUserResolver(userRepository), teamRepository, teamMemberRepository, teamRepoRepository);
        // git-CLI principals carry the username: every name here maps to an account of that name.
        lenient().when(userRepository.findUserByName(anyString()))
                .thenAnswer(inv -> Optional.of(account(inv.getArgument(0))));
        lenient().when(userRepository.findUserByEmail(anyString())).thenReturn(Optional.empty());
    }

    private static User account(String name) {
        User user = new User();
        user.setName(name);
        return user;
    }

    /** A browser (JWT/OAuth) principal: carries the email of the account with the given username. */
    private Principal emailPrincipal(String email, String username) {
        when(userRepository.findUserByEmail(email)).thenReturn(Optional.of(account(username)));
        return principal(email);
    }

    /** A principal whose name is exactly the value given (mirrors a git-CLI basic-auth username). */
    private static Principal principal(String name) {
        return () -> name;
    }

    private RepositoryGit existing(String owner, String repo, boolean isPrivate) {
        RepositoryGit entity = new RepositoryGit();
        entity.setRepoName(repo);
        entity.setPrivate(isPrivate);
        when(repositoryDB.findByOwnerNameAndRepoName(owner, repo)).thenReturn(Optional.of(entity));
        return entity;
    }

    /** Gives {@code username} an explicit collaborator grant on the repository. */
    private void grant(RepositoryGit repo, String username, RepositoryPermission permission) {
        Collaborator collaborator = new Collaborator();
        collaborator.setRepository(repo);
        collaborator.setPermission(permission);
        when(collaboratorRepository.findByRepositoryAndUsername(repo, username))
                .thenReturn(Optional.of(collaborator));
    }

    // ---------- READ ----------

    @Test
    void publicRepo_isReadableByAnyAuthenticatedUser() {
        existing("alice", "repo", false);
        assertThatCode(() -> guard.requireReadAccess("alice", "repo", principal("bob")))
                .doesNotThrowAnyException();
    }

    @Test
    void privateRepo_isReadableByOwner() {
        existing("alice", "repo", true);
        assertThatCode(() -> guard.requireReadAccess("alice", "repo", principal("alice")))
                .doesNotThrowAnyException();
    }

    @Test
    void privateRepo_ownerViaOAuthEmailPrincipal_isReadable() {
        // OAuth/JWT principals carry the email; the guard resolves it to the account's username.
        existing("alice", "repo", true);
        Principal alice = emailPrincipal("alice@example.com", "alice");
        assertThatCode(() -> guard.requireReadAccess("alice", "repo", alice))
                .doesNotThrowAnyException();
    }

    @Test
    void sameEmailLocalPart_doesNotGrantOwnership() {
        // Regression: identity used to be the email's local part, so alice@evil.com was "alice".
        existing("alice", "repo", true);
        Principal impostor = emailPrincipal("alice@evil.com", "alice-2");
        assertThatThrownBy(() -> guard.requireReadAccess("alice", "repo", impostor))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(guard.isOwner("alice", impostor)).isFalse();
    }

    @Test
    void principalWithoutAccount_isTreatedAsAnonymous() {
        existing("alice", "repo", false);
        assertThatThrownBy(() -> guard.requireReadAccess("alice", "repo", principal("ghost@nowhere.io")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void privateRepo_isHiddenFromNonOwner_asNotFound() {
        // Masked as 404 (not 403) so private repos cannot be enumerated over git or REST.
        existing("alice", "repo", true);
        assertThatThrownBy(() -> guard.requireReadAccess("alice", "repo", principal("bob")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void privateRepo_isHiddenFromAnonymous_asNotFound() {
        existing("alice", "repo", true);
        assertThatThrownBy(() -> guard.requireReadAccess("alice", "repo", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void missingRepo_isNotFound() {
        when(repositoryDB.findByOwnerNameAndRepoName("alice", "ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> guard.requireReadAccess("alice", "ghost", principal("alice")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- WRITE ----------

    @Test
    void write_allowedForOwner() {
        existing("alice", "repo", false);
        assertThatCode(() -> guard.requireWriteAccess("alice", "repo", principal("alice")))
                .doesNotThrowAnyException();
    }

    @Test
    void write_toPublicRepoByNonOwner_isForbidden() {
        // Existence is already public, so a 403 (not a masking 404) is correct here.
        existing("alice", "repo", false);
        assertThatThrownBy(() -> guard.requireWriteAccess("alice", "repo", principal("bob")))
                .isInstanceOf(AccessDeniedDomainException.class);
    }

    @Test
    void write_toPrivateRepoByNonOwner_isMaskedAsNotFound() {
        existing("alice", "repo", true);
        assertThatThrownBy(() -> guard.requireWriteAccess("alice", "repo", principal("bob")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- COLLABORATORS ----------

    @Test
    void privateRepo_isReadableByAReadCollaborator() {
        RepositoryGit repo = existing("alice", "repo", true);
        grant(repo, "bob", RepositoryPermission.READ);
        assertThatCode(() -> guard.requireReadAccess("alice", "repo", principal("bob")))
                .doesNotThrowAnyException();
    }

    @Test
    void readCollaborator_cannotWrite() {
        // Visible to them, so an honest 403 rather than a masking 404.
        RepositoryGit repo = existing("alice", "repo", true);
        grant(repo, "bob", RepositoryPermission.READ);
        assertThatThrownBy(() -> guard.requireWriteAccess("alice", "repo", principal("bob")))
                .isInstanceOf(AccessDeniedDomainException.class);
    }

    @Test
    void writeCollaborator_canWrite() {
        RepositoryGit repo = existing("alice", "repo", true);
        grant(repo, "bob", RepositoryPermission.WRITE);
        assertThatCode(() -> guard.requireWriteAccess("alice", "repo", principal("bob")))
                .doesNotThrowAnyException();
    }

    @Test
    void writeCollaborator_cannotAdminister() {
        RepositoryGit repo = existing("alice", "repo", false);
        grant(repo, "bob", RepositoryPermission.WRITE);
        assertThatThrownBy(() -> guard.requireAdminAccess("alice", "repo", principal("bob")))
                .isInstanceOf(AccessDeniedDomainException.class);
    }

    @Test
    void adminCollaborator_canAdministerButIsNotOwner() {
        RepositoryGit repo = existing("alice", "repo", false);
        grant(repo, "bob", RepositoryPermission.ADMIN);
        assertThatCode(() -> guard.requireAdminAccess("alice", "repo", principal("bob")))
                .doesNotThrowAnyException();
        // Deleting the repo and managing collaborators stay owner-only, even for ADMIN.
        assertThatThrownBy(() -> guard.requireOwner("alice", "repo", principal("bob")))
                .isInstanceOf(AccessDeniedDomainException.class);
    }

    @Test
    void owner_outranksEverything() {
        existing("alice", "repo", true);
        assertThatCode(() -> guard.requireOwner("alice", "repo", principal("alice")))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.requireAdminAccess("alice", "repo", principal("alice")))
                .doesNotThrowAnyException();
    }

    @Test
    void effectivePermission_reportsTheGrantedLevel() {
        RepositoryGit repo = existing("alice", "repo", true);
        grant(repo, "bob", RepositoryPermission.WRITE);
        assertThat(guard.effectivePermission("alice", "repo", principal("bob")))
                .contains(RepositoryPermission.WRITE);
    }

    // ---------- ORGANIZATIONS ----------

    private void teams(String org, String user, TeamGrant... grants) {
        when(teamRepository.findGrants(org, user, null)).thenReturn(java.util.List.of(grants));
    }

    @Test
    void orgOwnersTeamMember_isOwnerOfEveryOrgRepo() {
        existing("acme", "repo", true);
        // The Owners team matches every repository, so it always shows up as an owner-level grant.
        teams("acme", "carol", new TeamGrant(RepositoryPermission.ADMIN, true));
        when(teamMemberRepository.isOwner("acme", "carol")).thenReturn(true);
        assertThatCode(() -> guard.requireOwner("acme", "repo", principal("carol"))).doesNotThrowAnyException();
    }

    @Test
    void teamGrant_givesItsLevelOnCoveredRepos() {
        existing("acme", "repo", true);
        teams("acme", "dave", new TeamGrant(RepositoryPermission.WRITE, false));
        assertThatCode(() -> guard.requireWriteAccess("acme", "repo", principal("dave"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireAdminAccess("acme", "repo", principal("dave")))
                .isInstanceOf(AccessDeniedDomainException.class);
    }

    @Test
    void privateOrgRepo_notCoveredByAnyTeam_isHidden() {
        existing("acme", "secret", true);
        teams("acme", "dave"); // member of a team, but it does not cover this repository
        assertThatThrownBy(() -> guard.requireReadAccess("acme", "secret", principal("dave")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void strongestOfTeamsAndCollaboratorGrantWins() {
        RepositoryGit repo = existing("acme", "repo", true);
        teams("acme", "dave", new TeamGrant(RepositoryPermission.READ, false), new TeamGrant(RepositoryPermission.WRITE, false));
        grant(repo, "dave", RepositoryPermission.ADMIN);
        assertThat(guard.effectivePermission("acme", "repo", principal("dave"))).contains(RepositoryPermission.ADMIN);
    }

    @Test
    void ownersTeamGrant_isOwnerLevel() {
        existing("acme", "repo", true);
        teams("acme", "erin", new TeamGrant(RepositoryPermission.ADMIN, true));
        assertThat(guard.effectivePermission("acme", "repo", principal("erin"))).contains(RepositoryPermission.ADMIN);
    }

    @Test
    void privateListing_includesTeamGrantsAndCollaborations() {
        org.os.gitbase.git.entity.Team team = new org.os.gitbase.git.entity.Team();
        when(teamRepository.findTeamsOfMember("acme", "dave")).thenReturn(java.util.List.of(team));
        when(teamRepoRepository.findRepoNamesOfTeams(java.util.List.of(team))).thenReturn(java.util.List.of("api"));
        when(collaboratorRepository.findCollaboratingRepoNames("acme", "dave")).thenReturn(java.util.List.of("docs"));

        var visible = guard.privateRepoVisibility("acme", principal("dave"));

        assertThat(visible.canSee("api")).isTrue();
        assertThat(visible.canSee("docs")).isTrue();
        assertThat(visible.canSee("secret")).isFalse();
    }

    @Test
    void privateListing_teamCoveringEverything_seesAll() {
        org.os.gitbase.git.entity.Team everything = new org.os.gitbase.git.entity.Team();
        everything.setIncludesAllRepositories(true);
        when(teamRepository.findTeamsOfMember("acme", "dave")).thenReturn(java.util.List.of(everything));
        assertThat(guard.privateRepoVisibility("acme", principal("dave")).canSee("anything")).isTrue();
    }

    @Test
    void effectivePermission_isEmptyForAHiddenPrivateRepo() {
        existing("alice", "repo", true);
        assertThat(guard.effectivePermission("alice", "repo", principal("bob"))).isEmpty();
    }
}

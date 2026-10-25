package org.os.gitbase.git.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.exception.AccessDeniedDomainException;
import org.os.gitbase.exception.ConflictException;
import org.os.gitbase.exception.InvalidStateException;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.git.dto.OrganizationDtos.CreateOrganizationDto;
import org.os.gitbase.git.dto.OrganizationDtos.CreateTeamDto;
import org.os.gitbase.git.dto.OrganizationDtos.UpdateTeamDto;
import org.os.gitbase.git.entity.Issue;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.entity.enums.RepositoryPermission;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.os.gitbase.security.RepositoryAccessGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Organizations and teams end to end against PostgreSQL: the JPQL behind permission resolution
 * ({@code findGrants}, owner/member checks), the Owners-team invariants, and repository deletion
 * with dependent rows. Fixtures are created inside the test transaction and rolled back.
 * Needs a reachable PostgreSQL and {@code PRIV_KEY}/{@code PUB_KEY}, like {@code GitbaseApplicationTests}.
 */
@SpringBootTest
@Transactional
class OrganizationIntegrationTest {

    private static final String ORG = "it-org-acme";

    @Autowired private OrganizationService organizations;
    @Autowired private TeamService teams;
    @Autowired private RepositoryAccessGuard guard;
    @Autowired private GitService gitService;
    @Autowired private GitRepositoryDB repositoryDB;
    @PersistenceContext private EntityManager em;

    private User alice;

    @BeforeEach
    void setUp() {
        alice = user("it-org-alice");
        user("it-org-dave");
        user("it-org-erin");
        organizations.create(alice.getName(), new CreateOrganizationDto(ORG, "Acme Inc.", null));
        em.flush();
    }

    private User user(String name) {
        User user = new User();
        user.setName(name);
        user.setEmail(name + "@example.test");
        em.persist(user);
        return user;
    }

    private RepositoryGit orgRepo(String name, boolean isPrivate) {
        User org = em.createQuery("SELECT u FROM User u WHERE u.name = :n", User.class)
                .setParameter("n", ORG).getSingleResult();
        RepositoryGit repo = new RepositoryGit();
        repo.setOwner(org);
        repo.setRepoName(name);
        repo.setPrivate(isPrivate);
        repo.setCreatedAt(LocalDateTime.now());
        em.persist(repo);
        em.flush();
        return repo;
    }

    private static Principal as(String name) {
        return () -> name;
    }

    @Test
    void creatorOwnsTheOrganizationAndItsRepositories() {
        orgRepo("private-thing", true);

        var dto = organizations.get(ORG, "it-org-alice");
        assertThat(dto.viewerIsOwner()).isTrue();
        assertThat(dto.viewerCanCreateRepository()).isTrue();
        assertThat(guard.isOwner(ORG, as("it-org-alice"))).isTrue();
        assertThat(guard.effectivePermission(ORG, "private-thing", as("it-org-alice"))).contains(RepositoryPermission.ADMIN);
        // Organizations share the user namespace: the name is now taken for everyone.
        assertThatThrownBy(() -> organizations.create("it-org-dave", new CreateOrganizationDto("IT-ORG-ACME", null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void teamGrantsApplyOnlyToTheirRepositories() {
        orgRepo("api", true);
        orgRepo("secret", true);
        teams.create(ORG, new CreateTeamDto("backend", null, "WRITE", false, false));
        teams.addMember(ORG, "backend", "it-org-dave");
        teams.addRepository(ORG, "backend", "api");
        em.flush();

        assertThat(guard.effectivePermission(ORG, "api", as("it-org-dave"))).contains(RepositoryPermission.WRITE);
        assertThatThrownBy(() -> guard.requireReadAccess(ORG, "secret", as("it-org-dave")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(guard.privateRepoVisibility(ORG, as("it-org-dave")).canSee("api")).isTrue();
        assertThat(guard.privateRepoVisibility(ORG, as("it-org-dave")).canSee("secret")).isFalse();
        assertThat(organizations.canCreateRepository(ORG, "it-org-dave")).isFalse();
    }

    @Test
    void aTeamCoveringAllRepositoriesReachesNewOnesWithoutAnyRows() {
        teams.create(ORG, new CreateTeamDto("readers", null, "READ", true, true));
        teams.addMember(ORG, "readers", "it-org-erin");
        orgRepo("created-later", true);

        assertThat(guard.effectivePermission(ORG, "created-later", as("it-org-erin"))).contains(RepositoryPermission.READ);
        assertThat(organizations.canCreateRepository(ORG, "it-org-erin")).isTrue();
        assertThat(teams.repositories(ORG, "readers", 0, 30).items()).extracting(r -> r.name()).contains("created-later");
    }

    @Test
    void theOwnersTeamKeepsItsShapeAndAtLeastOneMember() {
        assertThatThrownBy(() -> teams.removeMember(ORG, "Owners", "it-org-alice"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("last owner");
        assertThatThrownBy(() -> organizations.removeMember(ORG, "it-org-alice"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> teams.delete(ORG, "owners"))
                .isInstanceOf(InvalidStateException.class);
        assertThatThrownBy(() -> teams.update(ORG, "Owners", new UpdateTeamDto(null, null, "READ", null, null)))
                .isInstanceOf(InvalidStateException.class);

        // With a second owner, the first may leave.
        teams.addMember(ORG, "Owners", "it-org-dave");
        em.flush();
        assertThatCode(() -> organizations.removeMember(ORG, "it-org-alice")).doesNotThrowAnyException();
    }

    @Test
    void anOrganizationWithRepositoriesCannotBeDeleted() {
        orgRepo("keep-me", false);
        assertThatThrownBy(() -> organizations.delete(ORG)).isInstanceOf(ConflictException.class);
    }

    @Test
    void organizationsCannotBeCollaboratorsOrTeamMembers() {
        organizations.create("it-org-alice", new CreateOrganizationDto("it-org-other", null, null));
        em.flush();
        assertThatThrownBy(() -> teams.addMember(ORG, "Owners", "it-org-other"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletingARepositoryRemovesItsIssuesAndTeamGrants() {
        RepositoryGit repo = orgRepo("doomed", false);
        teams.create(ORG, new CreateTeamDto("devs", null, "WRITE", false, false));
        teams.addRepository(ORG, "devs", "doomed");
        Issue issue = new Issue();
        issue.setRepository(repo);
        issue.setNumber(1);
        issue.setTitle("will be purged");
        issue.setAuthor(alice);
        em.persist(issue);
        em.flush();

        // Used to fail with a foreign-key violation: none of these references cascade.
        assertThatCode(() -> gitService.deleteRepository(ORG, "doomed")).doesNotThrowAnyException();
        em.flush();
        em.clear();
        assertThat(repositoryDB.findByOwnerNameAndRepoName(ORG, "doomed")).isEmpty();
    }

    @Test
    void nonOwnersCannotPassTheOwnerCheck() {
        teams.create(ORG, new CreateTeamDto("admins", null, "ADMIN", true, true));
        teams.addMember(ORG, "admins", "it-org-dave");
        orgRepo("repo", true);
        em.flush();

        // ADMIN through a team is not ownership: deleting repos and managing collaborators stay owner-only.
        assertThat(guard.effectivePermission(ORG, "repo", as("it-org-dave"))).contains(RepositoryPermission.ADMIN);
        assertThatThrownBy(() -> guard.requireOwner(ORG, "repo", as("it-org-dave")))
                .isInstanceOf(AccessDeniedDomainException.class);
    }
}

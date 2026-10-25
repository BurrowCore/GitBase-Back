package org.os.gitbase.git.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.exception.AccessDeniedDomainException;
import org.os.gitbase.exception.ConflictException;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.git.dto.OrganizationDtos.CreateOrganizationDto;
import org.os.gitbase.git.dto.OrganizationDtos.CreateTeamDto;
import org.os.gitbase.git.entity.Issue;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.entity.enums.RepositoryPermission;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.os.gitbase.git.repository.IssueRepository;
import org.os.gitbase.security.RepositoryAccessGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Moving a personal repository into an organization, against PostgreSQL and a real folder on disk.
 * Fixtures live in the test transaction (rolled back) and a temporary repositories directory.
 * Needs PostgreSQL and {@code PRIV_KEY}/{@code PUB_KEY}, like {@code GitbaseApplicationTests}.
 */
@SpringBootTest
@Transactional
class RepositoryTransferIntegrationTest {

    private static final Path REPOS;

    static {
        try {
            REPOS = Files.createTempDirectory("gitbase-transfer-it");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void repositoriesPath(DynamicPropertyRegistry registry) {
        registry.add("gitbase.repositories-path", REPOS::toString);
    }

    private static final String ALICE = "xfer-alice";
    private static final String DAVE = "xfer-dave";
    private static final String ORG = "xfer-acme";

    @Autowired private RepositoryTransferService transfers;
    @Autowired private OrganizationService organizations;
    @Autowired private TeamService teams;
    @Autowired private RepositoryAccessGuard guard;
    @Autowired private GitRepositoryDB repositoryDB;
    @Autowired private IssueRepository issueRepository;
    @PersistenceContext private EntityManager em;

    private User alice;

    @BeforeEach
    void setUp() throws Exception {
        // The folder outlives each test's rolled-back transaction; start every test from empty.
        org.springframework.util.FileSystemUtils.deleteRecursively(REPOS);
        Files.createDirectories(REPOS);
        alice = user(ALICE);
        user(DAVE);
        organizations.create(ALICE, new CreateOrganizationDto(ORG, null, null));
        personalRepo(alice, "proj");
        em.flush();
    }

    private User user(String name) {
        User user = new User();
        user.setName(name);
        user.setEmail(name + "@example.test");
        em.persist(user);
        return user;
    }

    private RepositoryGit personalRepo(User owner, String name) throws Exception {
        RepositoryGit repo = new RepositoryGit();
        repo.setOwner(owner);
        repo.setRepoName(name);
        repo.setPrivate(true);
        repo.setCreatedAt(LocalDateTime.now());
        em.persist(repo);
        try (Repository git = new FileRepositoryBuilder()
                .setGitDir(REPOS.resolve(owner.getName()).resolve(name + ".git").toFile()).build()) {
            git.create(true);
        }
        Issue issue = new Issue();
        issue.setRepository(repo);
        issue.setNumber(1);
        issue.setTitle("travels with the repository");
        issue.setAuthor(owner);
        em.persist(issue);
        return repo;
    }

    private Path folder(String owner, String repo) {
        return REPOS.resolve(owner).resolve(repo + ".git");
    }

    @Test
    void movesRowFolderAndIssuesIntoTheOrganization() {
        var result = transfers.transfer(ALICE, "proj", ORG, ALICE);
        em.flush();

        assertThat(result.owner()).isEqualTo(ORG);
        assertThat(result.cloneUrlPath()).isEqualTo("/gitbase/" + ORG + "/proj.git");
        RepositoryGit moved = repositoryDB.findByOwnerNameAndRepoName(ORG, "proj").orElseThrow();
        assertThat(repositoryDB.findByOwnerNameAndRepoName(ALICE, "proj")).isEmpty();
        assertThat(issueRepository.findByRepositoryAndNumber(moved, 1)).isPresent();
        assertThat(Files.isDirectory(folder(ORG, "proj"))).isTrue();
        assertThat(Files.exists(folder(ALICE, "proj"))).isFalse();
        // Alice owns the organization, so she still owns the repository.
        assertThat(guard.isOwner(ORG, () -> ALICE)).isTrue();
    }

    @Test
    void aCreatorWhoIsNotAnOwnerKeepsAdminThroughACollaboratorGrant() throws Exception {
        teams.create(ORG, new CreateTeamDto("creators", null, "READ", false, true));
        teams.addMember(ORG, "creators", DAVE);
        User dave = em.createQuery("SELECT u FROM User u WHERE u.name = :n", User.class)
                .setParameter("n", DAVE).getSingleResult();
        personalRepo(dave, "tool");
        em.flush();

        transfers.transfer(DAVE, "tool", ORG, DAVE);
        em.flush();

        assertThat(guard.effectivePermission(ORG, "tool", () -> DAVE)).contains(RepositoryPermission.ADMIN);
    }

    @Test
    void ownersOfTheNewOrganizationLoseTheirNowRedundantGrant_othersKeepTheirs() {
        RepositoryGit repo = repositoryDB.findByOwnerNameAndRepoName(ALICE, "proj").orElseThrow();
        User dave = em.createQuery("SELECT u FROM User u WHERE u.name = :n", User.class)
                .setParameter("n", DAVE).getSingleResult();
        User erin = user("xfer-erin");
        for (User u : java.util.List.of(dave, erin)) {
            org.os.gitbase.git.entity.Collaborator grant = new org.os.gitbase.git.entity.Collaborator();
            grant.setRepository(repo);
            grant.setUser(u);
            grant.setPermission(RepositoryPermission.WRITE);
            em.persist(grant);
        }
        teams.addMember(ORG, "Owners", DAVE);
        em.flush();

        transfers.transfer(ALICE, "proj", ORG, ALICE);
        em.flush();
        em.clear();

        RepositoryGit moved = repositoryDB.findByOwnerNameAndRepoName(ORG, "proj").orElseThrow();
        assertThat(guard.effectivePermission(ORG, "proj", () -> DAVE)).contains(RepositoryPermission.ADMIN); // as owner
        assertThat(em.createQuery("SELECT c.user.name FROM Collaborator c WHERE c.repository = :r", String.class)
                .setParameter("r", moved).getResultList()).containsExactly("xfer-erin");
    }

    @Test
    void refusesANameAlreadyTakenInTheOrganization() throws Exception {
        User org = em.createQuery("SELECT u FROM User u WHERE u.name = :n", User.class)
                .setParameter("n", ORG).getSingleResult();
        personalRepo(org, "proj");
        em.flush();

        assertThatThrownBy(() -> transfers.transfer(ALICE, "proj", ORG, ALICE))
                .isInstanceOf(ConflictException.class);
        assertThat(Files.isDirectory(folder(ALICE, "proj"))).isTrue();
    }

    @Test
    void onlyIntoOrganizationsTheCallerMayCreateIn() {
        assertThatThrownBy(() -> transfers.transfer(ALICE, "proj", DAVE, ALICE))
                .isInstanceOf(ResourceNotFoundException.class); // a user, not an organization
        organizations.create(DAVE, new CreateOrganizationDto("xfer-other", null, null));
        em.flush();
        assertThatThrownBy(() -> transfers.transfer(ALICE, "proj", "xfer-other", ALICE))
                .isInstanceOf(AccessDeniedDomainException.class);
    }

    @Test
    void aRolledBackTransferPutsTheFolderBack() {
        transfers.transfer(ALICE, "proj", ORG, ALICE);
        assertThat(Files.isDirectory(folder(ORG, "proj"))).isTrue();

        TestTransaction.flagForRollback();
        TestTransaction.end();

        assertThat(Files.isDirectory(folder(ALICE, "proj"))).isTrue();
        assertThat(Files.exists(folder(ORG, "proj"))).isFalse();
    }
}

package org.os.gitbase.git.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.exception.ConflictException;
import org.os.gitbase.git.entity.RepositoryGit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One repository name per owner, ignoring case — checked by the service (409) and enforced by the
 * database (V4 {@code uk_repository_owner_lower_name}) for concurrent creates.
 */
@SpringBootTest
@Transactional
class RepositoryNameUniquenessTest {

    private static final Path REPOS;

    static {
        try {
            REPOS = Files.createTempDirectory("gitbase-unique-it");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void repositoriesPath(DynamicPropertyRegistry registry) {
        registry.add("gitbase.repositories-path", REPOS::toString);
    }

    private static final String OWNER = "uniq-owner";

    @Autowired private GitService gitService;
    @PersistenceContext private EntityManager em;

    private User owner;

    @BeforeEach
    void setUp() throws Exception {
        org.springframework.util.FileSystemUtils.deleteRecursively(REPOS);
        Files.createDirectories(REPOS);
        owner = new User();
        owner.setName(OWNER);
        owner.setEmail(OWNER + "@example.test");
        em.persist(owner);
        em.flush();
    }

    @Test
    void creatingTheSameNameAgain_inAnyCase_isAConflictAndTouchesNoFolder() {
        gitService.createRepository(OWNER, "Project", false, "main");

        assertThatThrownBy(() -> gitService.createRepository(OWNER, "project", false, "main"))
                .isInstanceOf(ConflictException.class);
        assertThat(REPOS.resolve(OWNER).resolve("project.git")).satisfiesAnyOf(
                p -> assertThat(p).doesNotExist(),
                // case-insensitive file system: the path resolves to the existing Project.git
                p -> assertThat(REPOS.resolve(OWNER).resolve("Project.git")).exists());
    }

    @Test
    void theDatabaseRefusesADuplicateEvenIfTheCheckIsRaced() {
        persistRepo("racer");
        persistRepo("RACER");

        assertThatThrownBy(() -> em.flush())
                .rootCause().hasMessageContaining("uk_repository_owner_lower_name");
    }

    private void persistRepo(String name) {
        RepositoryGit repo = new RepositoryGit();
        repo.setOwner(owner);
        repo.setRepoName(name);
        repo.setCreatedAt(LocalDateTime.now());
        em.persist(repo);
    }
}

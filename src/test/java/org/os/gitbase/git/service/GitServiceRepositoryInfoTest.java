package org.os.gitbase.git.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.os.gitbase.git.repository.RepositoryPurgeRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression for "the open pull request shows no merge button": JGit created repositories with
 * HEAD → master while users pushed main, so repo-info threw, the UI saw no permissions, and hid
 * the merge controls.
 */
class GitServiceRepositoryInfoTest {

    @TempDir
    Path tempDir;

    private GitRepositoryDB repositoryDB;
    private GitServiceImpl service;

    @BeforeEach
    void setUp() {
        repositoryDB = mock(GitRepositoryDB.class);
        UserRepository users = mock(UserRepository.class);
        when(users.findUserByName("alice")).thenReturn(Optional.of(new User()));
        when(repositoryDB.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new GitServiceImpl(repositoryDB, users, mock(RepositoryPurgeRepository.class), tempDir.toString());

        RepositoryGit entity = new RepositoryGit();
        entity.setRepoName("demo");
        when(repositoryDB.findByOwnerNameAndRepoName("alice", "demo")).thenReturn(Optional.of(entity));
    }

    private Repository open() throws Exception {
        return new FileRepositoryBuilder().setGitDir(tempDir.resolve("alice/demo.git").toFile()).setBare().build();
    }

    @Test
    void newRepositoriesPointHeadAtTheChosenDefaultBranch() throws Exception {
        service.createRepository("alice", "demo", false, "main");

        try (Repository repo = open()) {
            assertThat(repo.exactRef(Constants.HEAD).getTarget().getName()).isEqualTo("refs/heads/main");
        }
    }

    @Test
    void repoInfoSurvivesAHeadPointingAtAMissingBranch_andRepairsIt() throws Exception {
        // The old layout: bare repo with HEAD → master, but only "main" was ever pushed.
        Path work = tempDir.resolve("work");
        try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
            Files.writeString(work.resolve("README.md"), "hi\n");
            git.add().addFilepattern("README.md").call();
            git.commit().setMessage("initial").setAuthor("A", "a@example.com").call();
        }
        Path bare = tempDir.resolve("alice/demo.git");
        Files.createDirectories(bare.getParent());
        Git.cloneRepository().setURI(work.toUri().toString()).setDirectory(bare.toFile()).setBare(true).call().close();
        try (Repository repo = open()) {
            repo.updateRef(Constants.HEAD).link("refs/heads/master");
        }

        var info = service.getRepositoryInfo("alice", "demo");

        assertThat(info.getLastCommitTime()).isPositive();
        try (Repository repo = open()) {
            assertThat(repo.exactRef(Constants.HEAD).getTarget().getName()).isEqualTo("refs/heads/main");
        }
    }

    @Test
    void repoInfoOfAnEmptyRepositoryIsNotAnError() {
        service.createRepository("alice", "demo", true, "main");
        assertThat(service.getRepositoryInfo("alice", "demo").getLastCommitTime()).isZero();
    }
}

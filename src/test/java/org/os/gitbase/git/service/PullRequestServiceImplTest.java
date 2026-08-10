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
import org.os.gitbase.exception.AccessDeniedDomainException;
import org.os.gitbase.exception.ConflictException;
import org.os.gitbase.exception.InvalidStateException;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.git.dto.CreatePullRequestDto;
import org.os.gitbase.git.dto.MergePullRequestDto;
import org.os.gitbase.git.entity.PullRequest;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.entity.enums.PullRequestStatus;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.os.gitbase.git.repository.PullRequestCommentRepository;
import org.os.gitbase.git.repository.PullRequestRepository;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the pull-request state machine and the in-core merge engine.
 *
 * <p>The merge tests drive a <b>real bare repository</b> built with JGit in a temp directory,
 * because that is the only way to prove the bare-repo merge path actually works — a mocked
 * repository would happily pass while the real one fails for want of a worktree.
 */
class PullRequestServiceImplTest {

    private static final String OWNER = "alice";
    private static final String REPO = "demo";

    @TempDir
    Path tempDir;

    private PullRequestRepository pullRequestRepository;
    private PullRequestCommentRepository commentRepository;
    private GitRepositoryDB repositoryDB;
    private UserRepository userRepository;
    private GitService gitService;
    private PushSyncService pushSyncService;
    private ActivityService activityService;

    private PullRequestServiceImpl service;
    private RepositoryGit repoEntity;
    private User alice;

    @BeforeEach
    void setUp() {
        pullRequestRepository = mock(PullRequestRepository.class);
        commentRepository = mock(PullRequestCommentRepository.class);
        repositoryDB = mock(GitRepositoryDB.class);
        userRepository = mock(UserRepository.class);
        gitService = mock(GitService.class);
        pushSyncService = mock(PushSyncService.class);
        activityService = mock(ActivityService.class);

        service = new PullRequestServiceImpl(pullRequestRepository, commentRepository, repositoryDB,
                userRepository, gitService, pushSyncService, activityService, tempDir.toString());

        alice = new User();
        alice.setId(UUID.randomUUID());
        alice.setName(OWNER);
        alice.setEmail("alice@example.com");

        repoEntity = new RepositoryGit();
        repoEntity.setRepoName(REPO);
        repoEntity.setOwner(alice);

        when(repositoryDB.findByOwnerNameAndRepoName(OWNER, REPO)).thenReturn(Optional.of(repoEntity));
        when(userRepository.findUserByName(OWNER)).thenReturn(Optional.of(alice));
        when(commentRepository.countByPullRequest(any())).thenReturn(0L);
    }

    // ────────────────── create / state machine ──────────────────

    @Test
    void create_rejectsSameSourceAndTarget() {
        var request = new CreatePullRequestDto("t", null, "main", "main");
        assertThatThrownBy(() -> service.create(OWNER, REPO, OWNER, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be different");
    }

    @Test
    void create_rejectsDuplicateOpenPullRequest() {
        PullRequest existing = openPullRequest(7);
        when(pullRequestRepository.findByRepositoryAndSourceBranchAndTargetBranchAndStatus(
                repoEntity, "feature", "main", PullRequestStatus.OPEN)).thenReturn(Optional.of(existing));

        var request = new CreatePullRequestDto("t", null, "feature", "main");
        assertThatThrownBy(() -> service.create(OWNER, REPO, OWNER, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("#7");
    }

    @Test
    void create_rejectsMissingBranch() throws Exception {
        buildRepository(false); // has "main" only
        when(pullRequestRepository.findByRepositoryAndSourceBranchAndTargetBranchAndStatus(
                any(), anyString(), anyString(), any())).thenReturn(Optional.empty());

        var request = new CreatePullRequestDto("t", null, "does-not-exist", "main");
        assertThatThrownBy(() -> service.create(OWNER, REPO, OWNER, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Branch not found");
    }

    @Test
    void merge_rejectsAlreadyMergedPullRequest() {
        PullRequest pr = openPullRequest(1);
        pr.setStatus(PullRequestStatus.MERGED);
        givenPullRequest(pr);

        assertThatThrownBy(() -> service.merge(OWNER, REPO, 1, OWNER, new MergePullRequestDto("merge", null)))
                .isInstanceOf(InvalidStateException.class);
    }

    @Test
    void merge_rejectsUnsupportedStrategy() {
        givenPullRequest(openPullRequest(1));
        assertThatThrownBy(() -> service.merge(OWNER, REPO, 1, OWNER, new MergePullRequestDto("squash", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("squash");
    }

    @Test
    void close_rejectsUserWhoIsNeitherAuthorNorOwner() {
        givenPullRequest(openPullRequest(1));
        User bob = new User();
        bob.setName("bob");
        when(userRepository.findUserByName("bob")).thenReturn(Optional.of(bob));

        assertThatThrownBy(() -> service.close(OWNER, REPO, 1, "bob"))
                .isInstanceOf(AccessDeniedDomainException.class);
    }

    @Test
    void reopen_rejectsPullRequestThatIsNotClosed() {
        givenPullRequest(openPullRequest(1));
        assertThatThrownBy(() -> service.reopen(OWNER, REPO, 1, OWNER))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void list_rejectsUnknownStateFilter() {
        assertThatThrownBy(() -> service.list(OWNER, REPO, "BANANA", 0, 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BANANA");
    }

    // ────────────────── merge engine (real bare repo) ──────────────────

    @Test
    void merge_appliesCleanMergeAndMovesTargetBranch() throws Exception {
        buildRepository(true); // main + feature touching different files
        PullRequest pr = openPullRequest(1);
        givenPullRequest(pr);

        String mainBefore = resolve("main");

        var result = service.merge(OWNER, REPO, 1, OWNER, new MergePullRequestDto("merge", null));

        assertThat(result.merged()).isTrue();
        assertThat(result.sha()).isNotBlank();

        // The target branch must now point at the new merge commit.
        String mainAfter = resolve("main");
        assertThat(mainAfter).isEqualTo(result.sha()).isNotEqualTo(mainBefore);
        assertThat(pr.getStatus()).isEqualTo(PullRequestStatus.MERGED);
        assertThat(pr.getMergeCommitSha()).isEqualTo(result.sha());
    }

    @Test
    void merge_reportsConflictingPathsAndLeavesTargetUntouched() throws Exception {
        buildConflictingRepository(); // main + feature editing the SAME line
        PullRequest pr = openPullRequest(1);
        givenPullRequest(pr);

        String mainBefore = resolve("main");

        assertThatThrownBy(() -> service.merge(OWNER, REPO, 1, OWNER, new MergePullRequestDto("merge", null)))
                .isInstanceOf(ConflictException.class)
                .satisfies(thrown -> assertThat(((ConflictException) thrown).getConflicts())
                        .contains("shared.txt"));

        // A failed merge must not move the branch or flip the pull request's state.
        assertThat(resolve("main")).isEqualTo(mainBefore);
        assertThat(pr.getStatus()).isEqualTo(PullRequestStatus.OPEN);
    }

    @Test
    void merge_isNoOpWhenSourceAlreadyContainedInTarget() throws Exception {
        buildRepository(true);
        // Merge feature into itself: trivially already merged.
        PullRequest pr = openPullRequest(1);
        pr.setSourceBranch("main");
        pr.setTargetBranch("main");
        givenPullRequest(pr);

        String before = resolve("main");
        var result = service.merge(OWNER, REPO, 1, OWNER, new MergePullRequestDto("merge", null));

        assertThat(result.merged()).isTrue();
        assertThat(result.sha()).isEqualTo(before);
        assertThat(resolve("main")).isEqualTo(before); // unchanged — no empty merge commit
    }

    @Test
    void reopen_succeedsWhenSourceBranchStillExists() throws Exception {
        buildRepository(true);
        PullRequest pr = openPullRequest(1);
        pr.setStatus(PullRequestStatus.CLOSED);
        givenPullRequest(pr);

        assertThatCode(() -> service.reopen(OWNER, REPO, 1, OWNER)).doesNotThrowAnyException();
        assertThat(pr.getStatus()).isEqualTo(PullRequestStatus.OPEN);
    }

    @Test
    void reopen_failsWhenSourceBranchIsGone() throws Exception {
        buildRepository(false); // no "feature" branch
        PullRequest pr = openPullRequest(1);
        pr.setStatus(PullRequestStatus.CLOSED);
        givenPullRequest(pr);

        assertThatThrownBy(() -> service.reopen(OWNER, REPO, 1, OWNER))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("no longer exists");
    }

    // ────────────────── fixtures ──────────────────

    private PullRequest openPullRequest(int number) {
        PullRequest pr = new PullRequest();
        pr.setNumber(number);
        pr.setTitle("Add things");
        pr.setRepository(repoEntity);
        pr.setAuthor(alice);
        pr.setSourceBranch("feature");
        pr.setTargetBranch("main");
        pr.setStatus(PullRequestStatus.OPEN);
        return pr;
    }

    private void givenPullRequest(PullRequest pr) {
        when(pullRequestRepository.findByRepositoryAndNumber(repoEntity, pr.getNumber()))
                .thenReturn(Optional.of(pr));
        when(pullRequestRepository.save(any(PullRequest.class)))
                .thenAnswer((Answer<PullRequest>) inv -> inv.getArgument(0));
    }

    private Path bareRepoPath() {
        return tempDir.resolve(OWNER).resolve(REPO + ".git");
    }

    private String resolve(String branch) throws IOException {
        try (Repository repo = new FileRepositoryBuilder().setGitDir(bareRepoPath().toFile()).setBare().build()) {
            return repo.resolve(Constants.R_HEADS + branch).getName();
        }
    }

    /**
     * Creates a bare repo with a "main" branch, and optionally a "feature" branch that changes a
     * different file so the merge is clean.
     */
    private void buildRepository(boolean withFeatureBranch) throws Exception {
        Path work = tempDir.resolve("work-" + UUID.randomUUID());
        try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
            commit(git, work, "README.md", "hello", "initial");
            if (withFeatureBranch) {
                git.branchCreate().setName("feature").call();
                git.checkout().setName("feature").call();
                commit(git, work, "feature.txt", "new feature", "add feature");
                git.checkout().setName("main").call();
                commit(git, work, "other.txt", "unrelated", "main moves on");
            }
            cloneToBare(work);
        }
    }

    /** Both branches edit the same file on the same line → a genuine content conflict. */
    private void buildConflictingRepository() throws Exception {
        Path work = tempDir.resolve("work-" + UUID.randomUUID());
        try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
            commit(git, work, "shared.txt", "original", "initial");
            git.branchCreate().setName("feature").call();

            git.checkout().setName("feature").call();
            commit(git, work, "shared.txt", "feature version", "feature edit");

            git.checkout().setName("main").call();
            commit(git, work, "shared.txt", "main version", "main edit");

            cloneToBare(work);
        }
    }

    private void commit(Git git, Path work, String file, String content, String message) throws Exception {
        Files.writeString(work.resolve(file), content + "\n");
        git.add().addFilepattern(file).call();
        git.commit().setMessage(message).setAuthor("Alice", "alice@example.com").call();
    }

    /** Mirrors the working repo into the bare location the service reads from. */
    private void cloneToBare(Path work) throws Exception {
        Files.createDirectories(bareRepoPath().getParent());
        Git.cloneRepository()
                .setURI(work.toUri().toString())
                .setDirectory(bareRepoPath().toFile())
                .setBare(true)
                .setCloneAllBranches(true)
                .call()
                .close();
    }
}

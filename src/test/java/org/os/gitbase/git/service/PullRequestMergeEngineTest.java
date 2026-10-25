package org.os.gitbase.git.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.os.gitbase.exception.ConflictException;
import org.os.gitbase.exception.InvalidStateException;
import org.os.gitbase.git.entity.enums.MergeMethod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Squash and rebase on a <b>real bare repository</b> — the only way to prove the in-core path
 * works, since a mocked repository would pass while the real one fails for want of a worktree.
 */
class PullRequestMergeEngineTest {

    private static final PersonIdent PR_AUTHOR = new PersonIdent("Alice", "alice@example.com");
    private static final PersonIdent MERGER = new PersonIdent("Carol", "carol@example.com");

    @TempDir
    Path tempDir;

    private Path work;
    private Git git;
    private final PullRequestMergeEngine engine = new PullRequestMergeEngine();

    @BeforeEach
    void initWorkingRepository() throws Exception {
        work = tempDir.resolve("work");
        git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call();
        commit("README.md", "hello", "initial", PR_AUTHOR);
    }

    @AfterEach
    void close() {
        git.close();
    }

    // ───────────────────────── squash ─────────────────────────

    @Test
    void squash_landsOneCommitOnTopOfTheTarget_withGitHubStyleDefaultMessage() throws Exception {
        branch("feature");
        commit("a.txt", "a", "add a", PR_AUTHOR);
        commit("b.txt", "b", "add b", new PersonIdent("Bob", "bob@example.com"));
        checkout("main");
        commit("other.txt", "x", "main moves on", PR_AUTHOR);

        try (Repository repo = bare()) {
            RevCommit mainBefore = tip(repo, "main");
            String sha = engine.merge(repo, request(MergeMethod.SQUASH, null));

            RevCommit squash = commitAt(repo, sha);
            assertThat(tip(repo, "main").getName()).isEqualTo(sha);
            assertThat(squash.getParentCount()).isEqualTo(1);
            assertThat(squash.getParent(0)).isEqualTo(mainBefore);
            assertThat(filesIn(repo, squash)).contains("a.txt", "b.txt", "other.txt", "README.md");
            assertThat(squash.getAuthorIdent().getEmailAddress()).isEqualTo("alice@example.com");
            assertThat(squash.getCommitterIdent().getEmailAddress()).isEqualTo("carol@example.com");
            assertThat(squash.getFullMessage())
                    .startsWith("Add things (#7)\n\n* add a\n* add b")
                    .contains("Co-authored-by: Bob <bob@example.com>")
                    .doesNotContain("Co-authored-by: Alice");
        }
    }

    @Test
    void squash_usesAnExplicitMessageVerbatim() throws Exception {
        branch("feature");
        commit("a.txt", "a", "add a", PR_AUTHOR);
        checkout("main");

        try (Repository repo = bare()) {
            String sha = engine.merge(repo, request(MergeMethod.SQUASH, "Custom title\n\nbody"));
            assertThat(commitAt(repo, sha).getFullMessage()).isEqualTo("Custom title\n\nbody");
        }
    }

    @Test
    void squash_conflictLeavesTheTargetUntouched() throws Exception {
        buildConflict();
        try (Repository repo = bare()) {
            String before = tip(repo, "main").getName();
            assertThatThrownBy(() -> engine.merge(repo, request(MergeMethod.SQUASH, null)))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(e -> assertThat(((ConflictException) e).getConflicts()).contains("shared.txt"));
            assertThat(tip(repo, "main").getName()).isEqualTo(before);
        }
    }

    @Test
    void squash_isANoOpWhenTheChangesAlreadyLanded() throws Exception {
        // A retried squash, or the same change cherry-picked: the merged tree equals the target's.
        branch("feature");
        commit("a.txt", "a", "add a", PR_AUTHOR);
        checkout("main");
        commit("a.txt", "a", "same change, landed separately", PR_AUTHOR);

        try (Repository repo = bare()) {
            String before = tip(repo, "main").getName();
            assertThat(engine.merge(repo, request(MergeMethod.SQUASH, null))).isEqualTo(before);
            assertThat(tip(repo, "main").getName()).isEqualTo(before);
        }
    }

    @Test
    void squash_retriedAfterLanding_writesNothing() throws Exception {
        branch("feature");
        commit("a.txt", "a", "add a", PR_AUTHOR);
        checkout("main");
        commit("other.txt", "x", "main moves on", PR_AUTHOR);

        try (Repository repo = bare()) {
            String first = engine.merge(repo, request(MergeMethod.SQUASH, null));
            assertThat(engine.merge(repo, request(MergeMethod.SQUASH, null))).isEqualTo(first);
        }
    }

    @Test
    void squash_listsOnlyTheNewestCommitsPastTheCap() throws Exception {
        branch("feature");
        for (int i = 0; i < 105; i++) {
            commit("f.txt", "v" + i, "step " + i, PR_AUTHOR);
        }
        checkout("main");

        try (Repository repo = bare()) {
            String message = commitAt(repo, engine.merge(repo, request(MergeMethod.SQUASH, null))).getFullMessage();
            assertThat(message).contains("(showing the latest 100 commits)", "* step 104").doesNotContain("* step 4\n");
        }
    }

    @Test
    void expectedHeadSha_mismatchRefusesBeforeWritingAnything() throws Exception {
        branch("feature");
        commit("a.txt", "a", "add a", PR_AUTHOR);
        checkout("main");

        try (Repository repo = bare()) {
            String before = tip(repo, "main").getName();
            var stale = new PullRequestMergeEngine.MergeRequest("feature", "main", MergeMethod.MERGE, 7, "t", null,
                    PR_AUTHOR, MERGER, before); // main's sha, not feature's
            assertThatThrownBy(() -> engine.merge(repo, stale))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Head branch was modified");
            assertThat(tip(repo, "main").getName()).isEqualTo(before);
        }
    }

    // ───────────────────────── rebase ─────────────────────────

    @Test
    void rebase_dropsMergeCommitsButKeepsTheWorkTheyBroughtIn() throws Exception {
        branch("feature");
        commit("a.txt", "a", "add a", PR_AUTHOR);
        git.branchCreate().setName("side").call();
        git.checkout().setName("side").call();
        commit("side.txt", "s", "side work", PR_AUTHOR);
        checkout("feature");
        git.merge().include(git.getRepository().resolve("side")).setFastForward(
                org.eclipse.jgit.api.MergeCommand.FastForwardMode.NO_FF).setMessage("merge side").call();
        checkout("main");
        commit("other.txt", "x", "main moves on", PR_AUTHOR);

        try (Repository repo = bare()) {
            RevCommit tip = commitAt(repo, engine.merge(repo, request(MergeMethod.REBASE, null)));
            assertThat(filesIn(repo, tip)).contains("a.txt", "side.txt", "other.txt");
            // Linear: no commit on the landed range has two parents.
            for (RevCommit c = tip; !c.getShortMessage().equals("main moves on"); c = commitAt(repo, c.getParent(0).getName())) {
                assertThat(c.getParentCount()).isEqualTo(1);
                assertThat(c.getShortMessage()).isNotEqualTo("merge side");
            }
        }
    }

    @Test
    void rebase_unrelatedHistoriesAreRefused() throws Exception {
        git.checkout().setOrphan(true).setName("feature").call();
        git.rm().addFilepattern("README.md").call();
        commit("lonely.txt", "x", "unrelated root", PR_AUTHOR);
        checkout("main");

        try (Repository repo = bare()) {
            assertThatThrownBy(() -> engine.merge(repo, request(MergeMethod.REBASE, null)))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("unrelated histories");
        }
    }

    @Test
    void rebase_replaysEachCommitLinearly_keepingAuthorsAndMessages() throws Exception {
        branch("feature");
        commit("a.txt", "a", "add a", PR_AUTHOR);
        commit("b.txt", "b", "add b", new PersonIdent("Bob", "bob@example.com"));
        checkout("main");
        commit("other.txt", "x", "main moves on", PR_AUTHOR);

        try (Repository repo = bare()) {
            RevCommit mainBefore = tip(repo, "main");
            String sha = engine.merge(repo, request(MergeMethod.REBASE, "ignored by rebase"));

            RevCommit second = commitAt(repo, sha);
            RevCommit first = commitAt(repo, second.getParent(0).getName());
            assertThat(tip(repo, "main").getName()).isEqualTo(sha);
            assertThat(second.getParentCount()).isEqualTo(1);
            assertThat(first.getParent(0)).isEqualTo(mainBefore);

            assertThat(first.getShortMessage()).isEqualTo("add a");
            assertThat(second.getShortMessage()).isEqualTo("add b");
            assertThat(second.getAuthorIdent().getEmailAddress()).isEqualTo("bob@example.com");
            assertThat(second.getCommitterIdent().getEmailAddress()).isEqualTo("carol@example.com");
            assertThat(filesIn(repo, second)).contains("a.txt", "b.txt", "other.txt");
        }
    }

    @Test
    void rebase_fastForwardsWhenTheTargetHasNotMoved_keepingOriginalShas() throws Exception {
        branch("feature");
        commit("a.txt", "a", "add a", PR_AUTHOR);
        checkout("main");

        try (Repository repo = bare()) {
            String featureTip = tip(repo, "feature").getName();
            assertThat(engine.merge(repo, request(MergeMethod.REBASE, null))).isEqualTo(featureTip);
            assertThat(tip(repo, "main").getName()).isEqualTo(featureTip);
        }
    }

    @Test
    void rebase_dropsCommitsThatBecomeEmpty() throws Exception {
        branch("feature");
        commit("same.txt", "identical", "already upstream", PR_AUTHOR);
        commit("a.txt", "a", "add a", PR_AUTHOR);
        checkout("main");
        commit("same.txt", "identical", "same change landed on main", PR_AUTHOR);

        try (Repository repo = bare()) {
            RevCommit mainBefore = tip(repo, "main");
            RevCommit landed = commitAt(repo, engine.merge(repo, request(MergeMethod.REBASE, null)));
            assertThat(landed.getShortMessage()).isEqualTo("add a");
            assertThat(landed.getParent(0)).isEqualTo(mainBefore);
        }
    }

    @Test
    void rebase_conflictNamesTheCommitAndLeavesTheTargetUntouched() throws Exception {
        buildConflict();
        try (Repository repo = bare()) {
            String before = tip(repo, "main").getName();
            assertThatThrownBy(() -> engine.merge(repo, request(MergeMethod.REBASE, null)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("feature edit")
                    .satisfies(e -> assertThat(((ConflictException) e).getConflicts()).contains("shared.txt"));
            assertThat(tip(repo, "main").getName()).isEqualTo(before);
        }
    }

    @Test
    void rebase_refusesMoreCommitsThanTheCap() throws Exception {
        branch("feature");
        for (int i = 0; i <= PullRequestMergeEngine.MAX_REBASE_COMMITS; i++) {
            commit("f.txt", "v" + i, "step " + i, PR_AUTHOR);
        }
        checkout("main");
        commit("other.txt", "x", "main moves on", PR_AUTHOR);

        try (Repository repo = bare()) {
            assertThatThrownBy(() -> engine.merge(repo, request(MergeMethod.REBASE, null)))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("Too many commits");
        }
    }

    // ───────────────────────── shared ─────────────────────────

    @Test
    void everyMethod_isANoOpWhenTheSourceIsAlreadyContained() throws Exception {
        branch("feature");
        checkout("main");
        commit("other.txt", "x", "main ahead", PR_AUTHOR);

        for (MergeMethod method : MergeMethod.values()) {
            try (Repository repo = bare()) {
                String before = tip(repo, "main").getName();
                assertThat(engine.merge(repo, request(method, null))).isEqualTo(before);
                assertThat(tip(repo, "main").getName()).isEqualTo(before);
            }
        }
    }

    // ───────────────────────── fixtures ─────────────────────────

    private PullRequestMergeEngine.MergeRequest request(MergeMethod method, String message) {
        return new PullRequestMergeEngine.MergeRequest("feature", "main", method, 7, "Add things", message,
                PR_AUTHOR, MERGER, null);
    }

    private void buildConflict() throws Exception {
        commit("shared.txt", "original", "base", PR_AUTHOR);
        branch("feature");
        commit("shared.txt", "feature version", "feature edit", PR_AUTHOR);
        checkout("main");
        commit("shared.txt", "main version", "main edit", PR_AUTHOR);
    }

    private void commit(String file, String content, String message, PersonIdent author) throws Exception {
        Files.writeString(work.resolve(file), content + "\n");
        git.add().addFilepattern(file).call();
        git.commit().setMessage(message).setAuthor(author).setCommitter(author).setAllowEmpty(true).call();
    }

    private void branch(String name) throws Exception {
        git.branchCreate().setName(name).call();
        git.checkout().setName(name).call();
    }

    private void checkout(String name) throws Exception {
        git.checkout().setName(name).call();
    }

    /** A fresh bare mirror of the working repository — what the engine operates on in production. */
    private Repository bare() throws Exception {
        Path bare = tempDir.resolve("bare-" + System.nanoTime() + ".git");
        Git.cloneRepository().setURI(work.toUri().toString()).setDirectory(bare.toFile())
                .setBare(true).setCloneAllBranches(true).call().close();
        return new FileRepositoryBuilder().setGitDir(bare.toFile()).setBare().build();
    }

    private static RevCommit tip(Repository repo, String branch) throws Exception {
        try (RevWalk walk = new RevWalk(repo)) {
            return walk.parseCommit(repo.resolve(Constants.R_HEADS + branch));
        }
    }

    private static RevCommit commitAt(Repository repo, String sha) throws Exception {
        try (RevWalk walk = new RevWalk(repo)) {
            return walk.parseCommit(repo.resolve(sha));
        }
    }

    private static List<String> filesIn(Repository repo, RevCommit commit) throws Exception {
        List<String> files = new ArrayList<>();
        try (TreeWalk tree = new TreeWalk(repo)) {
            tree.addTree(commit.getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                files.add(tree.getPathString());
            }
        }
        return files;
    }
}

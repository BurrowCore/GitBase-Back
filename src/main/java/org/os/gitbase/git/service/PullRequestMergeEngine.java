package org.os.gitbase.git.service;

import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.merge.ThreeWayMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.os.gitbase.exception.ConflictException;
import org.os.gitbase.exception.InvalidStateException;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.git.entity.enums.MergeMethod;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lands a pull request on its target branch with one of the three {@link MergeMethod}s.
 *
 * <p><b>Everything happens in-core.</b> Repositories are bare, so there is no worktree for
 * {@code git merge}/{@code git rebase}, and no temporary clone is made either; we run
 * JGit's in-memory three-way merger, write trees and commits straight into the object database,
 * and move the branch with a <b>compare-and-swap</b> ref update so a concurrent push is never
 * silently overwritten. A failure at any step leaves the target branch untouched: new objects
 * that are never referenced are simply unreachable garbage.
 */
@Component
public class PullRequestMergeEngine {

    /** Upper bound on commits replayed by a rebase, so a request cannot walk unbounded history (ADR-004). */
    static final int MAX_REBASE_COMMITS = 250;

    /** Upper bound on commit subjects listed in a default squash message. */
    private static final int MAX_SQUASH_SUBJECTS = 100;

    /**
     * What to land and how.
     *
     * @param message         explicit commit message, or null for the method's default
     * @param squashAuthor    author of a squash commit (the pull-request author)
     * @param committer       the user performing the merge
     * @param expectedHeadSha the source tip the merger reviewed; null skips the check
     */
    public record MergeRequest(String sourceBranch, String targetBranch, MergeMethod method,
                               int number, String title, String message,
                               PersonIdent squashAuthor, PersonIdent committer, String expectedHeadSha) {
    }

    /**
     * Applies the merge and moves the target branch.
     *
     * @return the new tip of the target branch (or its unchanged tip when already up to date)
     * @throws ConflictException      when the changes cannot be applied cleanly (409)
     * @throws InvalidStateException  when the method cannot be used for this pull request (405)
     */
    public String merge(Repository repo, MergeRequest request) throws IOException {
        String targetRef = Constants.R_HEADS + request.targetBranch();
        ObjectId targetId = requireBranch(repo, request.targetBranch(), "Target");
        ObjectId sourceId = requireBranch(repo, request.sourceBranch(), "Source");

        // Never land commits nobody reviewed: someone may have pushed since the page was loaded.
        if (request.expectedHeadSha() != null && !request.expectedHeadSha().isBlank()
                && !sourceId.getName().equalsIgnoreCase(request.expectedHeadSha().trim())) {
            throw new ConflictException("Head branch was modified. Review and try the merge again.");
        }

        try (RevWalk walk = new RevWalk(repo); ObjectInserter inserter = repo.newObjectInserter()) {
            RevCommit target = walk.parseCommit(targetId);
            RevCommit source = walk.parseCommit(sourceId);

            // Nothing to land — never create an empty merge/squash commit.
            if (walk.isMergedInto(source, target)) {
                return target.getName();
            }

            ObjectId newTip = switch (request.method()) {
                case MERGE -> mergeCommit(repo, inserter, target, source, request);
                case SQUASH -> squashCommit(repo, inserter, walk, target, source, request);
                case REBASE -> rebase(repo, inserter, walk, target, source, request);
            };
            inserter.flush();

            if (!newTip.equals(target)) {
                moveBranch(repo, targetRef, targetId, newTip, request);
            }
            return newTip.getName();
        }
    }

    // ────────────────────────────── merge ──────────────────────────────

    private ObjectId mergeCommit(Repository repo, ObjectInserter inserter, RevCommit target, RevCommit source,
                                 MergeRequest request) throws IOException {
        ObjectId tree = mergeTrees(repo, target, source, request);
        String message = orDefault(request.message(),
                "Merge pull request #" + request.number() + ": " + request.title());
        return insertCommit(inserter, tree, message, request.committer(), request.committer(),
                target, source);
    }

    // ────────────────────────────── squash ──────────────────────────────

    /**
     * One commit, parented on the target, whose tree is the clean three-way merge result. When that
     * tree is the target's own (the changes already landed — a retried request, or a cherry-pick)
     * nothing is written: squash commits are not ancestors of the source, so the ancestry no-op
     * check in {@link #merge} cannot catch this case.
     */
    private ObjectId squashCommit(Repository repo, ObjectInserter inserter, RevWalk walk, RevCommit target,
                                  RevCommit source, MergeRequest request) throws IOException {
        ObjectId tree = mergeTrees(repo, target, source, request);
        if (tree.equals(target.getTree())) {
            return target;
        }
        String message = request.message() != null && !request.message().isBlank()
                ? request.message().trim()
                : defaultSquashMessage(walk, target, source, request);
        return insertCommit(inserter, tree, message, request.squashAuthor(), request.committer(), target);
    }

    /**
     * {@code "<title> (#N)"}, then the squashed
     * commit subjects and a {@code Co-authored-by} trailer for every other commit author, so
     * squashing does not erase who contributed. Bounded: past {@value #MAX_SQUASH_SUBJECTS}
     * commits only the newest are listed, with a note saying so.
     */
    private String defaultSquashMessage(RevWalk walk, RevCommit target, RevCommit source,
                                        MergeRequest request) throws IOException {
        List<RevCommit> newestFirst = newestCommitsToLand(walk, target, source, MAX_SQUASH_SUBJECTS + 1);
        boolean truncated = newestFirst.size() > MAX_SQUASH_SUBJECTS;
        List<RevCommit> commits = new ArrayList<>(newestFirst.subList(0, Math.min(newestFirst.size(), MAX_SQUASH_SUBJECTS)));
        Collections.reverse(commits);

        StringBuilder message = new StringBuilder(request.title()).append(" (#").append(request.number()).append(')');
        if (!commits.isEmpty()) {
            message.append("\n\n");
            if (truncated) {
                message.append("(showing the latest ").append(MAX_SQUASH_SUBJECTS).append(" commits)\n");
            }
            commits.forEach(c -> message.append("* ").append(c.getShortMessage()).append('\n'));
        }

        PersonIdent squashAuthor = request.squashAuthor();
        Set<String> coAuthors = new LinkedHashSet<>();
        for (RevCommit commit : commits) {
            PersonIdent author = commit.getAuthorIdent();
            // Match on name too: an author without a stored email falls back to a no-reply address
            // that never equals their real commit email.
            boolean isSquashAuthor = author.getEmailAddress().equalsIgnoreCase(squashAuthor.getEmailAddress())
                    || author.getName().equalsIgnoreCase(squashAuthor.getName());
            if (!isSquashAuthor) {
                coAuthors.add("Co-authored-by: " + author.getName() + " <" + author.getEmailAddress() + ">");
            }
        }
        if (!coAuthors.isEmpty()) {
            message.append('\n').append(String.join("\n", coAuthors));
        }
        return message.toString().stripTrailing();
    }

    // ────────────────────────────── rebase ──────────────────────────────

    /**
     * Replays each source commit onto the target, keeping its author and message and making the
     * merger the committer — what {@code git rebase} does. Merge commits are dropped (their changes
     * arrive through the commits they merged) and commits that become empty are skipped, both
     * matching git's defaults. When the target is an ancestor of the source the branch is simply
     * fast-forwarded, keeping the original SHAs.
     */
    private ObjectId rebase(Repository repo, ObjectInserter inserter, RevWalk walk, RevCommit target,
                            RevCommit source, MergeRequest request) throws IOException {
        if (walk.isMergedInto(target, source)) {
            return source;
        }

        // Count with a streaming walk first: a topological sort reads the whole range before
        // yielding anything, so it may only run once the range is known to be small (ADR-004 S5).
        if (newestCommitsToLand(walk, target, source, MAX_REBASE_COMMITS + 1).size() > MAX_REBASE_COMMITS) {
            throw new InvalidStateException("Too many commits to rebase (more than " + MAX_REBASE_COMMITS
                    + "). Use a merge commit or squash instead.");
        }
        List<RevCommit> commits = oldestFirstCommitsToLand(walk, target, source);

        RevCommit tip = target;
        for (RevCommit commit : commits) {
            if (commit.getParentCount() > 1) {
                continue;
            }
            if (commit.getParentCount() == 0) {
                throw new InvalidStateException(
                        "Cannot rebase: " + request.sourceBranch() + " and " + request.targetBranch()
                                + " have unrelated histories");
            }

            ThreeWayMerger merger = newMerger(repo);
            merger.setBase(commit.getParent(0));
            if (!merger.merge(tip, commit)) {
                throw new ConflictException("Rebase conflict while applying " + commit.abbreviate(7).name()
                        + " \"" + commit.getShortMessage() + "\"", unmergedPaths(merger));
            }

            ObjectId tree = merger.getResultTreeId();
            if (tree.equals(tip.getTree())) {
                continue; // became empty on top of the target — git rebase drops these
            }
            ObjectId rewritten = insertCommit(inserter, tree, commit.getFullMessage(),
                    commit.getAuthorIdent(), request.committer(), tip);
            inserter.flush(); // the next merger reads through its own reader
            tip = walk.parseCommit(rewritten);
        }
        return tip;
    }

    // ────────────────────────────── shared ──────────────────────────────

    /**
     * Up to {@code limit} commits reachable from {@code source} but not {@code target}, newest
     * first. Unsorted, so JGit streams them and the walk really stops at the limit.
     */
    private List<RevCommit> newestCommitsToLand(RevWalk walk, RevCommit target, RevCommit source, int limit)
            throws IOException {
        walk.reset();
        walk.sort(RevSort.NONE);
        walk.markStart(walk.parseCommit(source));
        walk.markUninteresting(walk.parseCommit(target));

        List<RevCommit> commits = new ArrayList<>();
        for (RevCommit commit : walk) {
            commits.add(commit);
            if (commits.size() >= limit) {
                break;
            }
        }
        walk.reset();
        return commits;
    }

    /**
     * All commits reachable from {@code source} but not {@code target}, parents before children.
     * The topological sort materialises the whole range, so callers must bound it first with
     * {@link #newestCommitsToLand}.
     */
    private List<RevCommit> oldestFirstCommitsToLand(RevWalk walk, RevCommit target, RevCommit source)
            throws IOException {
        walk.reset();
        walk.sort(RevSort.TOPO);
        walk.sort(RevSort.REVERSE, true);
        walk.markStart(walk.parseCommit(source));
        walk.markUninteresting(walk.parseCommit(target));

        List<RevCommit> commits = new ArrayList<>();
        walk.forEach(commits::add);
        walk.reset();
        walk.sort(RevSort.NONE);
        return commits;
    }

    private ObjectId mergeTrees(Repository repo, RevCommit target, RevCommit source, MergeRequest request)
            throws IOException {
        ThreeWayMerger merger = newMerger(repo);
        if (!merger.merge(target, source)) {
            throw new ConflictException(
                    "Merge conflict between " + request.sourceBranch() + " and " + request.targetBranch(),
                    unmergedPaths(merger));
        }
        return merger.getResultTreeId();
    }

    /** inCore=true: merge entirely in memory, which is the only option for a bare repository. */
    private ThreeWayMerger newMerger(Repository repo) {
        return (ThreeWayMerger) MergeStrategy.RECURSIVE.newMerger(repo, true);
    }

    private List<String> unmergedPaths(ThreeWayMerger merger) {
        return merger instanceof ResolveMerger resolveMerger
                ? new ArrayList<>(resolveMerger.getUnmergedPaths())
                : List.of();
    }

    private ObjectId insertCommit(ObjectInserter inserter, ObjectId tree, String message,
                                  PersonIdent author, PersonIdent committer, ObjectId... parents)
            throws IOException {
        CommitBuilder builder = new CommitBuilder();
        builder.setTreeId(tree);
        builder.setParentIds(parents);
        builder.setAuthor(author);
        builder.setCommitter(committer);
        builder.setMessage(message);
        return inserter.insert(builder);
    }

    /** Compare-and-swap: if someone pushed to the target since we read it, fail rather than clobber. */
    private void moveBranch(Repository repo, String targetRef, ObjectId expectedOld, ObjectId newTip,
                            MergeRequest request) throws IOException {
        RefUpdate update = repo.updateRef(targetRef);
        update.setNewObjectId(newTip);
        update.setExpectedOldObjectId(expectedOld);
        update.setRefLogMessage(request.method().wireName() + " pull request #" + request.number(), false);

        RefUpdate.Result result = update.update();
        if (result != RefUpdate.Result.FAST_FORWARD
                && result != RefUpdate.Result.NEW
                && result != RefUpdate.Result.FORCED) {
            throw new ConflictException(
                    "Could not update " + request.targetBranch() + " (" + result + ") — it may have moved. Try again.");
        }
    }

    private ObjectId requireBranch(Repository repo, String branch, String role) throws IOException {
        ObjectId id = repo.resolve(Constants.R_HEADS + branch);
        if (id == null) {
            throw new ResourceNotFoundException(role + " branch no longer exists: " + branch);
        }
        return id;
    }

    private static String orDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }
}

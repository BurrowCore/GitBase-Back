package org.os.gitbase.git.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.git.entity.Branch;
import org.os.gitbase.git.entity.Commit;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.entity.enums.ActivityType;
import org.os.gitbase.git.repository.BranchRepository;
import org.os.gitbase.git.repository.CommitRepository;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mirrors a CLI push into the database after JGit has updated the refs on disk
 * (the gitea/github model). For every successful branch update it walks the new
 * commits, upserts them into {@code commits}, moves the {@code branches} head,
 * and records a {@code PUSH} activity. Reads (history, diff) stay live on JGit;
 * this DB mirror exists to power activity feeds and pull-request bases.
 *
 * <p>This runs in a post-receive hook: the push has already succeeded, so this
 * method must never throw — any failure is logged and swallowed.
 */
@Slf4j
@Service
public class PushSyncService {

    private static final String BASE_PATH = "./gitbase/repositories";

    private final GitRepositoryDB repositoryDB;
    private final CommitRepository commitRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final ActivityService activityService;

    public PushSyncService(GitRepositoryDB repositoryDB,
                           CommitRepository commitRepository,
                           BranchRepository branchRepository,
                           UserRepository userRepository,
                           ActivityService activityService) {
        this.repositoryDB = repositoryDB;
        this.commitRepository = commitRepository;
        this.branchRepository = branchRepository;
        this.userRepository = userRepository;
        this.activityService = activityService;
    }

    @Transactional
    public void syncPush(Repository repo, String username, String repoName, Collection<ReceiveCommand> commands) {
        Optional<RepositoryGit> repoOpt = repositoryDB.findByOwnerNameAndRepoName(username, repoName);
        if (repoOpt.isEmpty()) {
            log.warn("Push sync skipped: no DB record for {}/{}", username, repoName);
            return;
        }
        RepositoryGit repoEntity = repoOpt.get();

        int totalNewCommits = 0;
        List<String> touchedBranches = new ArrayList<>();

        for (ReceiveCommand cmd : commands) {
            if (cmd.getResult() != ReceiveCommand.Result.OK) {
                continue;
            }
            String refName = cmd.getRefName();
            if (refName == null || !refName.startsWith(Constants.R_HEADS)) {
                continue; // only mirror branch updates (ignore tags/notes for now)
            }
            String branchName = refName.substring(Constants.R_HEADS.length());

            try {
                if (cmd.getType() == ReceiveCommand.Type.DELETE) {
                    branchRepository.findByNameAndRepository(branchName, repoEntity)
                            .ifPresent(branchRepository::delete);
                    log.info("Push sync: deleted branch {} for {}/{}", branchName, username, repoName);
                    continue;
                }

                int created = syncBranch(repo, repoEntity, branchName, cmd.getOldId(), cmd.getNewId());
                totalNewCommits += created;
                touchedBranches.add(branchName);
            } catch (Exception e) {
                log.error("Push sync failed for {}/{} branch {}: {}", username, repoName, branchName, e.getMessage(), e);
            }
        }

        if (!touchedBranches.isEmpty()) {
            try {
                String branchLabel = String.join(", ", touchedBranches);
                String message = totalNewCommits > 0
                        ? "Pushed " + totalNewCommits + " commit(s) to " + branchLabel
                        : "Updated " + branchLabel;
                activityService.logActivity(ActivityType.PUSH, username, repoName, message);
            } catch (Exception e) {
                log.error("Failed to log PUSH activity for {}/{}: {}", username, repoName, e.getMessage());
            }
        }
    }

    /**
     * Lazy, O(1)-guarded sync used on the read path. If the repository's current HEAD commit is
     * already stored, returns immediately (one indexed lookup). Otherwise it reconciles once. This
     * replaces eager startup loading: only repositories that are actually accessed get synced, and
     * only when their tip has advanced beyond what the DB knows — so it scales to millions of repos.
     * Never throws; reads stay live on JGit regardless.
     */
    @Transactional
    public void ensureSynced(String username, String repoName) {
        try {
            File gitDir = new File(BASE_PATH + "/" + username + "/" + repoName + ".git");
            if (!gitDir.exists()) {
                return;
            }
            try (Repository repo = new FileRepositoryBuilder().setGitDir(gitDir).setBare().build()) {
                ObjectId head = repo.resolve(Constants.HEAD);
                if (head == null) {
                    return; // empty / unborn repository — nothing to sync
                }
                if (commitRepository.findByCommitHash(head.getName()).isPresent()) {
                    return; // tip already mirrored — fast path
                }
                reconcile(repo, username, repoName);
            }
        } catch (Exception e) {
            log.warn("Lazy sync failed for {}/{}: {}", username, repoName, e.getMessage());
        }
    }

    /**
     * Reconciles a repository's current on-disk state into the DB: walks every local branch and
     * upserts its commits + head. Idempotent (dedup on commit hash), so it is safe to run on
     * startup and after pushes to back-fill repositories that were pushed before sync existed.
     */
    @Transactional
    public void reconcile(Repository repo, String username, String repoName) {
        Optional<RepositoryGit> repoOpt = repositoryDB.findByOwnerNameAndRepoName(username, repoName);
        if (repoOpt.isEmpty()) {
            log.warn("Reconcile skipped: no DB record for {}/{}", username, repoName);
            return;
        }
        RepositoryGit repoEntity = repoOpt.get();

        int total = 0;
        try {
            for (Ref ref : repo.getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
                String branchName = ref.getName().substring(Constants.R_HEADS.length());
                ObjectId tip = ref.getObjectId();
                if (tip == null) {
                    continue;
                }
                try {
                    total += syncBranch(repo, repoEntity, branchName, ObjectId.zeroId(), tip);
                } catch (Exception e) {
                    log.error("Reconcile failed for {}/{} branch {}: {}", username, repoName, branchName, e.getMessage());
                }
            }
            if (total > 0) {
                log.info("Reconciled {}/{}: {} new commit(s) imported", username, repoName, total);
            }
        } catch (Exception e) {
            log.error("Reconcile failed for {}/{}: {}", username, repoName, e.getMessage(), e);
        }
    }

    /** Walks new commits on a branch, persists them, and moves the branch head. Returns # newly inserted. */
    private int syncBranch(Repository repo, RepositoryGit repoEntity, String branchName,
                           ObjectId oldId, ObjectId newId) throws Exception {
        if (newId == null || ObjectId.zeroId().equals(newId)) {
            return 0;
        }

        int inserted = 0;
        Map<String, Commit> persistedByHash = new HashMap<>();

        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(newId));

            if (oldId != null && !ObjectId.zeroId().equals(oldId)) {
                try {
                    walk.markUninteresting(walk.parseCommit(oldId));
                } catch (Exception ignore) {
                    // old tip may be unreachable (force push) — fall through and dedup via DB
                }
            }
            // Bound the walk: don't re-import commits already reachable from other branches.
            for (Ref other : repo.getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
                if (other.getName().equals(Constants.R_HEADS + branchName)) {
                    continue;
                }
                ObjectId otherId = other.getObjectId();
                if (otherId != null) {
                    try {
                        walk.markUninteresting(walk.parseCommit(otherId));
                    } catch (Exception ignore) {
                        // ignore unreadable refs
                    }
                }
            }

            // Oldest-first so a commit's parents are persisted before it.
            List<RevCommit> newest = new ArrayList<>();
            for (RevCommit c : walk) {
                newest.add(c);
            }
            for (int i = newest.size() - 1; i >= 0; i--) {
                RevCommit c = newest.get(i);
                Commit persisted = upsertCommit(repoEntity, c, persistedByHash);
                if (persisted != null && persisted.getId() == null) {
                    inserted++;
                }
            }
        }

        // Move (or create) the branch head to the new tip.
        String headHash = newId.getName();
        Commit headCommit = commitRepository.findByCommitHash(headHash)
                .orElse(persistedByHash.get(headHash));

        Branch branch = branchRepository.findByNameAndRepository(branchName, repoEntity)
                .orElseGet(() -> {
                    Branch b = new Branch();
                    b.setName(branchName);
                    b.setRepository(repoEntity);
                    return b;
                });
        branch.setCommit(headCommit);
        if (!branch.isDefault() && isLikelyDefault(repo, branchName)
                && branchRepository.findByRepository(repoEntity).stream().noneMatch(Branch::isDefault)) {
            branch.setDefault(true);
        }
        branchRepository.save(branch);

        return inserted;
    }

    /** Inserts the commit if its hash is not already stored; links parents that exist. */
    private Commit upsertCommit(RepositoryGit repoEntity, RevCommit c, Map<String, Commit> cache) {
        String hash = c.getName();
        Optional<Commit> existing = commitRepository.findByCommitHash(hash);
        if (existing.isPresent()) {
            cache.put(hash, existing.get());
            return existing.get();
        }

        Commit commit = new Commit();
        commit.setRepository(repoEntity);
        commit.setCommitHash(hash);
        commit.setMessage(c.getFullMessage() != null ? c.getFullMessage().trim() : "");
        commit.setCommittedAt(LocalDateTime.ofInstant(
                Instant.ofEpochSecond(c.getCommitTime()), ZoneId.systemDefault()));
        commit.setAuthor(resolveAuthor(c, repoEntity));

        // Link any parents we have already persisted in this walk or in the DB.
        for (RevCommit parent : c.getParents()) {
            Commit parentEntity = cache.get(parent.getName());
            if (parentEntity == null) {
                parentEntity = commitRepository.findByCommitHash(parent.getName()).orElse(null);
            }
            if (parentEntity != null) {
                commit.getParents().add(parentEntity);
            }
        }

        Commit saved = commitRepository.save(commit);
        cache.put(hash, saved);
        return saved;
    }

    /** Maps the git author email to a platform user; falls back to the repo owner so author is never null. */
    private User resolveAuthor(RevCommit c, RepositoryGit repoEntity) {
        PersonIdent ident = c.getAuthorIdent();
        if (ident != null && ident.getEmailAddress() != null && !ident.getEmailAddress().isBlank()) {
            User byEmail = userRepository.findUserByEmail(ident.getEmailAddress()).orElse(null);
            if (byEmail != null) {
                return byEmail;
            }
        }
        return repoEntity.getOwner();
    }

    private boolean isLikelyDefault(Repository repo, String branchName) {
        if ("main".equals(branchName) || "master".equals(branchName)) {
            return true;
        }
        try {
            Ref head = repo.exactRef(Constants.HEAD);
            if (head != null && head.isSymbolic() && head.getTarget() != null) {
                return (Constants.R_HEADS + branchName).equals(head.getTarget().getName());
            }
        } catch (Exception ignore) {
            // ignore
        }
        return false;
    }
}

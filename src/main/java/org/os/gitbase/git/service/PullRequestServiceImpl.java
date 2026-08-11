package org.os.gitbase.git.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.merge.ThreeWayMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.exception.AccessDeniedDomainException;
import org.os.gitbase.exception.ConflictException;
import org.os.gitbase.exception.InvalidStateException;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.git.dto.*;
import org.os.gitbase.git.entity.PullRequest;
import org.os.gitbase.git.entity.PullRequestComment;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.entity.enums.ActivityType;
import org.os.gitbase.git.entity.enums.PullRequestStatus;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.os.gitbase.git.repository.PullRequestCommentRepository;
import org.os.gitbase.git.repository.PullRequestRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Pull-request lifecycle implementation.
 *
 * <p>Two principles shape this class:
 * <ul>
 *   <li><b>Git is the source of truth.</b> Branch existence, commit counts and diffs are read live
 *       from JGit; the database stores only the pull-request record itself (ADR-004).</li>
 *   <li><b>Merges happen in-core.</b> Repositories are bare, so there is no worktree for
 *       {@code git merge} to operate on. We run a three-way merge in memory, write the resulting
 *       tree and a merge commit straight into the object database, then move the branch ref with a
 *       compare-and-swap so a concurrent push cannot be silently overwritten.</li>
 * </ul>
 */
@Service
@Slf4j
public class PullRequestServiceImpl implements PullRequestService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 30;

    /** Root of the bare-repository store. Configurable so tests can point at a temp directory. */
    private final String basePath;

    private final PullRequestRepository pullRequestRepository;
    private final PullRequestCommentRepository commentRepository;
    private final GitRepositoryDB repositoryDB;
    private final UserRepository userRepository;
    private final GitService gitService;
    private final PushSyncService pushSyncService;
    private final ActivityService activityService;

    public PullRequestServiceImpl(PullRequestRepository pullRequestRepository,
                                  PullRequestCommentRepository commentRepository,
                                  GitRepositoryDB repositoryDB,
                                  UserRepository userRepository,
                                  GitService gitService,
                                  PushSyncService pushSyncService,
                                  ActivityService activityService,
                                  @Value("${gitbase.repositories-path:./gitbase/repositories}") String basePath) {
        this.basePath = basePath;
        this.pullRequestRepository = pullRequestRepository;
        this.commentRepository = commentRepository;
        this.repositoryDB = repositoryDB;
        this.userRepository = userRepository;
        this.gitService = gitService;
        this.pushSyncService = pushSyncService;
        this.activityService = activityService;
    }

    // ────────────────────────────── create ──────────────────────────────

    @Override
    @Transactional
    public PullRequestDto create(String owner, String repoName, String actor, CreatePullRequestDto request) {
        RepositoryGit repoEntity = requireRepository(owner, repoName);
        User author = requireUser(actor);

        String source = request.sourceBranch().trim();
        String target = request.targetBranch().trim();
        if (source.equals(target)) {
            throw new IllegalArgumentException("Source and target branches must be different");
        }

        // Cheap indexed lookup first, so a duplicate fails before we touch the filesystem.
        pullRequestRepository
                .findByRepositoryAndSourceBranchAndTargetBranchAndStatus(
                        repoEntity, source, target, PullRequestStatus.OPEN)
                .ifPresent(existing -> {
                    throw new ConflictException(
                            "An open pull request already exists for " + source + " → " + target
                                    + " (#" + existing.getNumber() + ")");
                });

        // Branch existence + tip SHAs come from git, not from the mirror tables.
        String headSha;
        String baseSha;
        try (Repository repo = openRepository(owner, repoName)) {
            headSha = requireBranch(repo, source);
            baseSha = requireBranch(repo, target);
        } catch (IOException e) {
            throw new RuntimeException("Failed to open repository " + owner + "/" + repoName, e);
        }

        PullRequest pr = new PullRequest();
        pr.setRepository(repoEntity);
        pr.setNumber(nextNumber(repoEntity));
        pr.setTitle(request.title().trim());
        pr.setDescription(request.description());
        pr.setSourceBranch(source);
        pr.setTargetBranch(target);
        pr.setHeadSha(headSha);
        pr.setBaseSha(baseSha);
        pr.setAuthor(author);
        pr.setStatus(PullRequestStatus.OPEN);

        PullRequest saved = pullRequestRepository.save(pr);

        activityService.logActivity(ActivityType.PULL_REQUEST, actor, repoName,
                "[ " + repoName + " ] Opened pull request #" + saved.getNumber() + ": " + saved.getTitle());

        return toDto(saved, owner, repoName);
    }

    /**
     * Next per-repository pull-request number.
     *
     * <p>Read-then-increment races under concurrent creates; the unique constraint on
     * {@code (repository_id, number)} is what actually guarantees correctness — a loser gets a
     * constraint violation rather than a duplicate number.
     */
    private int nextNumber(RepositoryGit repository) {
        Integer max = pullRequestRepository.findMaxNumber(repository);
        return (max == null ? 0 : max) + 1;
    }

    // ────────────────────────────── read ──────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PullRequestPageDto list(String owner, String repoName, String state, int page, int size) {
        RepositoryGit repoEntity = requireRepository(owner, repoName);

        int safeSize = Math.clamp(size <= 0 ? DEFAULT_PAGE_SIZE : size, 1, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "number"));

        PullRequestStatus filter = parseState(state);
        Page<PullRequest> result = filter == null
                ? pullRequestRepository.findByRepository(repoEntity, pageable)
                : pullRequestRepository.findByRepositoryAndStatus(repoEntity, filter, pageable);

        List<PullRequestSummaryDto> rows = result.getContent().stream()
                .map(this::toSummary)
                .toList();

        long openCount = pullRequestRepository.countByRepositoryAndStatus(repoEntity, PullRequestStatus.OPEN);
        long closedCount = pullRequestRepository.countByRepositoryAndStatus(repoEntity, PullRequestStatus.CLOSED)
                + pullRequestRepository.countByRepositoryAndStatus(repoEntity, PullRequestStatus.MERGED);

        return new PullRequestPageDto(rows, safePage, safeSize, result.getTotalElements(),
                result.getTotalPages(), result.hasNext(), openCount, closedCount);
    }

    /** {@code null} means "no filter"; {@code all} is accepted as an explicit no-filter. */
    private PullRequestStatus parseState(String state) {
        if (state == null || state.isBlank() || state.equalsIgnoreCase("all")) {
            return null;
        }
        try {
            return PullRequestStatus.valueOf(state.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown state '" + state + "' (expected OPEN, CLOSED, MERGED or ALL)");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PullRequestDto get(String owner, String repoName, int number) {
        return toDto(requirePullRequest(owner, repoName, number), owner, repoName);
    }

    @Override
    @Transactional(readOnly = true)
    public CompareDto diff(String owner, String repoName, int number) {
        PullRequest pr = requirePullRequest(owner, repoName, number);
        // Three-way against the merge base (Q3 in the spec) — GitService.compare already does this.
        return gitService.compare(owner, repoName, pr.getTargetBranch(), pr.getSourceBranch());
    }

    // ────────────────────────────── merge ──────────────────────────────

    @Override
    @Transactional
    public MergeResultDto merge(String owner, String repoName, int number, String actor, MergePullRequestDto request) {
        PullRequest pr = requirePullRequest(owner, repoName, number);
        requireOpen(pr, "merge");

        String method = request == null || request.mergeMethod() == null || request.mergeMethod().isBlank()
                ? "merge" : request.mergeMethod().trim().toLowerCase();
        if (!method.equals("merge")) {
            throw new IllegalArgumentException(
                    "Only the 'merge' strategy is supported in this version (got '" + method + "')");
        }

        User merger = requireUser(actor);
        String message = (request != null && request.commitMessage() != null && !request.commitMessage().isBlank())
                ? request.commitMessage().trim()
                : "Merge pull request #" + pr.getNumber() + ": " + pr.getTitle();

        String mergeSha;
        try (Repository repo = openRepository(owner, repoName)) {
            mergeSha = performMerge(repo, pr, merger, message);
            // Mirror the new commit + moved branch head into the DB while the repo is still open.
            pushSyncService.reconcile(repo, owner, repoName);
        } catch (IOException e) {
            throw new RuntimeException("Failed to merge pull request #" + number, e);
        }

        pr.setStatus(PullRequestStatus.MERGED);
        pr.setMergedAt(LocalDateTime.now());
        pr.setMergedBy(merger);
        pr.setMergeCommitSha(mergeSha);
        pullRequestRepository.save(pr);

        activityService.logActivity(ActivityType.PULL_REQUEST, actor, repoName,
                "[ " + repoName + " ] Merged pull request #" + pr.getNumber() + ": " + pr.getTitle());

        return new MergeResultDto(true, mergeSha, "Pull request successfully merged");
    }

    /**
     * Runs the three-way merge and moves the target branch.
     *
     * @return SHA of the resulting merge commit (or of the existing tip when already up to date)
     * @throws ConflictException when the merge cannot be applied cleanly
     */
    private String performMerge(Repository repo, PullRequest pr, User merger, String message) throws IOException {
        String targetRef = Constants.R_HEADS + pr.getTargetBranch();
        ObjectId targetId = repo.resolve(targetRef);
        ObjectId sourceId = repo.resolve(Constants.R_HEADS + pr.getSourceBranch());

        if (targetId == null) {
            throw new ResourceNotFoundException("Target branch no longer exists: " + pr.getTargetBranch());
        }
        if (sourceId == null) {
            throw new ResourceNotFoundException("Source branch no longer exists: " + pr.getSourceBranch());
        }

        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit targetCommit = walk.parseCommit(targetId);
            RevCommit sourceCommit = walk.parseCommit(sourceId);

            // Nothing to do — the source is already contained in the target.
            if (walk.isMergedInto(sourceCommit, targetCommit)) {
                return targetCommit.getName();
            }

            // inCore=true: merge entirely in memory, which is the only option for a bare repo.
            ThreeWayMerger merger3 = (ThreeWayMerger) MergeStrategy.RECURSIVE.newMerger(repo, true);
            boolean clean = merger3.merge(targetCommit, sourceCommit);
            if (!clean) {
                List<String> conflicts = merger3 instanceof ResolveMerger rm
                        ? new ArrayList<>(rm.getUnmergedPaths())
                        : List.of();
                throw new ConflictException(
                        "Merge conflict between " + pr.getSourceBranch() + " and " + pr.getTargetBranch(),
                        conflicts);
            }

            PersonIdent ident = new PersonIdent(
                    merger.getName() != null ? merger.getName() : "gitbase",
                    merger.getEmail() != null ? merger.getEmail() : "noreply@gitbase.local");

            CommitBuilder commitBuilder = new CommitBuilder();
            commitBuilder.setTreeId(merger3.getResultTreeId());
            commitBuilder.setParentIds(targetCommit.getId(), sourceCommit.getId());
            commitBuilder.setAuthor(ident);
            commitBuilder.setCommitter(ident);
            commitBuilder.setMessage(message);

            ObjectId mergeCommitId;
            try (ObjectInserter inserter = repo.newObjectInserter()) {
                mergeCommitId = inserter.insert(commitBuilder);
                inserter.flush();
            }

            // Compare-and-swap: if someone pushed to the target since we read its tip, fail loudly
            // rather than clobbering their work.
            RefUpdate refUpdate = repo.updateRef(targetRef);
            refUpdate.setNewObjectId(mergeCommitId);
            refUpdate.setExpectedOldObjectId(targetId);
            refUpdate.setRefLogMessage("merge pull request #" + pr.getNumber(), false);

            RefUpdate.Result result = refUpdate.update();
            if (result != RefUpdate.Result.FAST_FORWARD
                    && result != RefUpdate.Result.NEW
                    && result != RefUpdate.Result.FORCED) {
                throw new ConflictException(
                        "Could not update " + pr.getTargetBranch() + " (" + result + ") — it may have moved. Try again.");
            }
            return mergeCommitId.getName();
        }
    }

    // ────────────────────────────── close / reopen ──────────────────────────────

    @Override
    @Transactional
    public PullRequestDto close(String owner, String repoName, int number, String actor) {
        PullRequest pr = requirePullRequest(owner, repoName, number);
        requireOpen(pr, "close");
        requireAuthorOrOwner(pr, owner, actor, "close");

        pr.setStatus(PullRequestStatus.CLOSED);
        pr.setClosedAt(LocalDateTime.now());
        pullRequestRepository.save(pr);

        activityService.logActivity(ActivityType.PULL_REQUEST, actor, repoName,
                "[ " + repoName + " ] Closed pull request #" + pr.getNumber());

        return toDto(pr, owner, repoName);
    }

    @Override
    @Transactional
    public PullRequestDto reopen(String owner, String repoName, int number, String actor) {
        PullRequest pr = requirePullRequest(owner, repoName, number);
        if (pr.getStatus() != PullRequestStatus.CLOSED) {
            throw new InvalidStateException(
                    "Only a closed pull request can be reopened (this one is " + pr.getStatus() + ")");
        }
        requireAuthorOrOwner(pr, owner, actor, "reopen");

        // US-004: reopening requires the source branch to still exist.
        try (Repository repo = openRepository(owner, repoName)) {
            if (repo.resolve(Constants.R_HEADS + pr.getSourceBranch()) == null) {
                throw new InvalidStateException(
                        "Cannot reopen: source branch '" + pr.getSourceBranch() + "' no longer exists");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to open repository " + owner + "/" + repoName, e);
        }

        pr.setStatus(PullRequestStatus.OPEN);
        pr.setClosedAt(null);
        pullRequestRepository.save(pr);

        activityService.logActivity(ActivityType.PULL_REQUEST, actor, repoName,
                "[ " + repoName + " ] Reopened pull request #" + pr.getNumber());

        return toDto(pr, owner, repoName);
    }

    // ────────────────────────────── comments ──────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public CommentPageDto listComments(String owner, String repoName, int number, int page, int size) {
        PullRequest pr = requirePullRequest(owner, repoName, number);

        int safeSize = Math.clamp(size <= 0 ? DEFAULT_PAGE_SIZE : size, 1, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.ASC, "createdAt"));

        Page<PullRequestComment> result = commentRepository.findByPullRequest(pr, pageable);
        List<CommentDto> comments = result.getContent().stream().map(this::toCommentDto).toList();

        return new CommentPageDto(comments, safePage, safeSize, result.getTotalElements(), result.hasNext());
    }

    @Override
    @Transactional
    public CommentDto addComment(String owner, String repoName, int number, String actor, CreateCommentDto request) {
        PullRequest pr = requirePullRequest(owner, repoName, number);
        User author = requireUser(actor);

        PullRequestComment comment = new PullRequestComment();
        comment.setPullRequest(pr);
        comment.setAuthor(author);
        comment.setBody(request.body().trim());

        return toCommentDto(commentRepository.save(comment));
    }

    // ────────────────────────────── branch suggestions ──────────────────────────────

    /** How recently a branch must have been pushed to earn a banner. */
    private static final long RECENT_WINDOW_MILLIS = Duration.ofDays(7).toMillis();
    /** Upper bound on suggestions returned, so the walk stays cheap (ADR-004). */
    private static final int MAX_SUGGESTIONS = 5;

    @Override
    @Transactional(readOnly = true)
    public List<PullSuggestionDto> suggestions(String owner, String repoName) {
        RepositoryGit repoEntity = requireRepository(owner, repoName);
        Set<String> alreadyProposed = new HashSet<>(pullRequestRepository.findOpenSourceBranches(repoEntity));

        long cutoff = System.currentTimeMillis() - RECENT_WINDOW_MILLIS;
        List<PullSuggestionDto> found = new ArrayList<>();

        try (Repository repo = openRepository(owner, repoName)) {
            String defaultBranch = defaultBranchOf(repo);

            try (RevWalk walk = new RevWalk(repo)) {
                for (Ref ref : repo.getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
                    String branch = Repository.shortenRefName(ref.getName());
                    // A branch is only worth proposing if it isn't the merge target and isn't
                    // already the source of an open pull request.
                    if (branch.equals(defaultBranch) || alreadyProposed.contains(branch)) {
                        continue;
                    }
                    ObjectId tip = ref.getObjectId();
                    if (tip == null) {
                        continue;
                    }
                    RevCommit commit = walk.parseCommit(tip);
                    long committedAt = commit.getCommitTime() * 1000L;
                    if (committedAt < cutoff) {
                        continue;
                    }
                    found.add(new PullSuggestionDto(
                            branch,
                            tip.getName(),
                            tip.getName().substring(0, 7),
                            commit.getShortMessage(),
                            commit.getAuthorIdent() != null ? commit.getAuthorIdent().getName() : null,
                            committedAt));
                }
            }
        } catch (IOException | RuntimeException e) {
            // A banner is decorative: never fail the page over it.
            log.debug("Could not compute pull suggestions for {}/{}: {}", owner, repoName, e.getMessage());
            return List.of();
        }

        found.sort(Comparator.comparingLong(PullSuggestionDto::lastCommitAt).reversed());
        return found.size() > MAX_SUGGESTIONS ? List.copyOf(found.subList(0, MAX_SUGGESTIONS)) : List.copyOf(found);
    }

    /** Branch that HEAD points at, or {@code null} for a detached/empty repository. */
    private String defaultBranchOf(Repository repo) throws IOException {
        Ref head = repo.exactRef(Constants.HEAD);
        if (head != null && head.isSymbolic() && head.getTarget() != null) {
            return Repository.shortenRefName(head.getTarget().getName());
        }
        return null;
    }

    // ────────────────────────────── helpers ──────────────────────────────

    private Repository openRepository(String owner, String repoName) throws IOException {
        File gitDir = Paths.get(basePath, owner, repoName + ".git").toFile();
        return new FileRepositoryBuilder().setGitDir(gitDir).setBare().build();
    }

    /** Resolves a branch to its tip SHA, or fails with a 404-style error. */
    private String requireBranch(Repository repo, String branch) throws IOException {
        ObjectId id = repo.resolve(Constants.R_HEADS + branch);
        if (id == null) {
            throw new ResourceNotFoundException("Branch not found: " + branch);
        }
        return id.getName();
    }

    private RepositoryGit requireRepository(String owner, String repoName) {
        return repositoryDB.findByOwnerNameAndRepoName(owner, repoName)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Repository not found: " + owner + "/" + repoName));
    }

    private PullRequest requirePullRequest(String owner, String repoName, int number) {
        RepositoryGit repoEntity = requireRepository(owner, repoName);
        return pullRequestRepository.findByRepositoryAndNumber(repoEntity, number)
                .orElseThrow(() -> new ResourceNotFoundException("Pull request #" + number + " not found"));
    }

    private User requireUser(String username) {
        return userRepository.findUserByName(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
    }

    private void requireOpen(PullRequest pr, String action) {
        if (pr.getStatus() != PullRequestStatus.OPEN) {
            throw new InvalidStateException(
                    "Cannot " + action + " a pull request that is " + pr.getStatus());
        }
    }

    /** Closing/reopening is limited to the PR author and the repository owner. */
    private void requireAuthorOrOwner(PullRequest pr, String repoOwner, String actor, String action) {
        boolean isAuthor = pr.getAuthor() != null && actor.equals(pr.getAuthor().getName());
        if (!isAuthor && !actor.equals(repoOwner)) {
            throw new AccessDeniedDomainException(
                    "Only the pull request author or the repository owner can " + action + " it");
        }
    }

    private static Long toEpochMillis(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private UserSummaryDto toUserSummary(User user) {
        if (user == null) return null;
        return new UserSummaryDto(user.getName(), user.getFullName(), user.getProfilePictureUrl());
    }

    private PullRequestSummaryDto toSummary(PullRequest pr) {
        return new PullRequestSummaryDto(
                pr.getId(),
                pr.getNumber(),
                pr.getTitle(),
                pr.getStatus().name(),
                pr.getSourceBranch(),
                pr.getTargetBranch(),
                toUserSummary(pr.getAuthor()),
                commentRepository.countByPullRequest(pr),
                toEpochMillis(pr.getCreatedAt()),
                toEpochMillis(pr.getUpdatedAt()));
    }

    /**
     * Builds the detail payload. Commit and changed-file counts come from a live compare, which is
     * bounded by the same truncation caps as the diff endpoint; if the branches have since been
     * deleted the counts degrade to zero rather than failing the whole read.
     */
    private PullRequestDto toDto(PullRequest pr, String owner, String repoName) {
        int commitsCount = 0;
        int changedFiles = 0;
        if (pr.getStatus() == PullRequestStatus.OPEN) {
            Optional<CompareDto> comparison = safeCompare(owner, repoName, pr);
            if (comparison.isPresent()) {
                commitsCount = comparison.get().commits().size();
                changedFiles = comparison.get().files().size();
            }
        }

        return new PullRequestDto(
                pr.getId(),
                pr.getNumber(),
                pr.getTitle(),
                pr.getDescription(),
                pr.getStatus().name(),
                pr.getSourceBranch(),
                pr.getTargetBranch(),
                pr.getHeadSha(),
                pr.getBaseSha(),
                toUserSummary(pr.getAuthor()),
                toUserSummary(pr.getMergedBy()),
                pr.getMergeCommitSha(),
                commitsCount,
                changedFiles,
                commentRepository.countByPullRequest(pr),
                toEpochMillis(pr.getCreatedAt()),
                toEpochMillis(pr.getUpdatedAt()),
                toEpochMillis(pr.getMergedAt()),
                toEpochMillis(pr.getClosedAt()));
    }

    private Optional<CompareDto> safeCompare(String owner, String repoName, PullRequest pr) {
        try {
            return Optional.of(gitService.compare(owner, repoName, pr.getTargetBranch(), pr.getSourceBranch()));
        } catch (RuntimeException e) {
            log.debug("Compare failed for PR #{} in {}/{}: {}", pr.getNumber(), owner, repoName, e.getMessage());
            return Optional.empty();
        }
    }

    private CommentDto toCommentDto(PullRequestComment comment) {
        return new CommentDto(
                comment.getId(),
                toUserSummary(comment.getAuthor()),
                comment.getBody(),
                toEpochMillis(comment.getCreatedAt()),
                toEpochMillis(comment.getUpdatedAt()));
    }
}

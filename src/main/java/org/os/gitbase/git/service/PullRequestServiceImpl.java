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
import org.os.gitbase.git.entity.enums.MergeMethod;
import org.os.gitbase.git.entity.enums.PullRequestStatus;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.os.gitbase.git.repository.PullRequestCommentRepository;
import org.os.gitbase.git.repository.PullRequestRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pull-request lifecycle implementation.
 *
 * <p>Two principles shape this class:
 * <ul>
 *   <li><b>Git is the source of truth.</b> Branch existence, commit counts and diffs are read live
 *       from JGit; the database stores only the pull-request record itself (ADR-004).</li>
 *   <li><b>Merges happen in-core.</b> The git work (merge / squash / rebase, all in memory on the
 *       bare repository, ending in a compare-and-swap ref update) lives in
 *       {@link PullRequestMergeEngine}; this class owns the pull-request record around it.</li>
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
    private final ActivityService activityService;
    private final IssueNumberAllocator numberAllocator;
    private final PullRequestMergeEngine mergeEngine;
    private final ClosingKeywordParser closingKeywordParser;
    private final ClosingIssueResolver closingIssueResolver;
    private final ApplicationEventPublisher events;
    private final TransactionOperations transactions;

    public PullRequestServiceImpl(PullRequestRepository pullRequestRepository,
                                  PullRequestCommentRepository commentRepository,
                                  GitRepositoryDB repositoryDB,
                                  UserRepository userRepository,
                                  GitService gitService,
                                  ActivityService activityService,
                                  IssueNumberAllocator numberAllocator,
                                  PullRequestMergeEngine mergeEngine,
                                  ClosingKeywordParser closingKeywordParser,
                                  ClosingIssueResolver closingIssueResolver,
                                  ApplicationEventPublisher events,
                                  TransactionOperations transactions,
                                  @Value("${gitbase.repositories-path:./gitbase/repositories}") String basePath) {
        this.basePath = basePath;
        this.pullRequestRepository = pullRequestRepository;
        this.commentRepository = commentRepository;
        this.repositoryDB = repositoryDB;
        this.userRepository = userRepository;
        this.gitService = gitService;
        this.activityService = activityService;
        this.numberAllocator = numberAllocator;
        this.mergeEngine = mergeEngine;
        this.closingKeywordParser = closingKeywordParser;
        this.closingIssueResolver = closingIssueResolver;
        this.events = events;
        this.transactions = transactions;
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
     * Next per-repository number, drawn from the sequence <b>shared with issues</b> so a repo
     * reads #1, #2, #3 whatever the type — which is what
     * makes a bare "#12" reference unambiguous. The allocator locks the repository row, so
     * concurrent creates cannot claim the same number.
     */
    private int nextNumber(RepositoryGit repository) {
        return numberAllocator.allocate(repository);
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

    /** What the git work needs, captured in the read transaction so none is held during it. */
    private record MergePlan(Long pullRequestId, Long version, PullRequestMergeEngine.MergeRequest request) {
    }

    /**
     * Three steps, deliberately <b>not</b> one transaction (ADR-004 S6): a short read transaction
     * validates and captures the plan; the git work runs with no connection held; a short write
     * transaction records the result. The engine is idempotent (an already-landed source is a
     * no-op for every method), and the {@code @Version} check makes the losing side of two
     * concurrent merges a 409 rather than a second audit record.
     */
    @Override
    public MergeResultDto merge(String owner, String repoName, int number, String actor, MergePullRequestDto request) {
        MergeMethod method = MergeMethod.parse(request == null ? null : request.mergeMethod());

        MergePlan plan = transactions.execute(status -> {
            PullRequest pr = requirePullRequest(owner, repoName, number);
            requireOpen(pr, "merge");
            User merger = requireUser(actor);
            return new MergePlan(pr.getId(), pr.getVersion(), new PullRequestMergeEngine.MergeRequest(
                    pr.getSourceBranch(), pr.getTargetBranch(), method, pr.getNumber(), pr.getTitle(),
                    request == null ? null : request.commitMessage(),
                    identityOf(pr.getAuthor() != null ? pr.getAuthor() : merger), identityOf(merger),
                    request == null ? null : request.expectedHeadSha()));
        });

        String newTip;
        boolean intoDefaultBranch;
        try (Repository repo = openRepository(owner, repoName)) {
            // Git is the source of truth (DECISION-007) — the new commits and the moved branch
            // head need no mirroring; the next read sees them straight from JGit.
            newTip = mergeEngine.merge(repo, plan.request());
            intoDefaultBranch = plan.request().targetBranch().equals(defaultBranchOf(repo));
        } catch (IOException e) {
            throw new RuntimeException("Failed to merge pull request #" + number, e);
        }

        return transactions.execute(status -> recordMerge(plan, owner, repoName, actor, method, newTip,
                intoDefaultBranch));
    }

    private MergeResultDto recordMerge(MergePlan plan, String owner, String repoName, String actor,
                                       MergeMethod method, String newTip, boolean intoDefaultBranch) {
        PullRequest pr = pullRequestRepository.findById(plan.pullRequestId())
                .orElseThrow(() -> new ResourceNotFoundException("Pull request no longer exists"));
        if (pr.getStatus() != PullRequestStatus.OPEN || !Objects.equals(pr.getVersion(), plan.version())) {
            throw new ConflictException("Pull request #" + pr.getNumber() + " was merged or changed concurrently");
        }

        pr.setStatus(PullRequestStatus.MERGED);
        pr.setMergedAt(LocalDateTime.now());
        pr.setMergedBy(requireUser(actor));
        pr.setMergeCommitSha(newTip);
        pr.setMergeMethod(method);
        pullRequestRepository.save(pr);

        String verb = switch (method) {
            case MERGE -> "Merged";
            case SQUASH -> "Squashed and merged";
            case REBASE -> "Rebased and merged";
        };
        activityService.logActivity(ActivityType.PULL_REQUEST, actor, repoName,
                "[ " + repoName + " ] " + verb + " pull request #" + pr.getNumber() + ": " + pr.getTitle());

        // Handled after this transaction commits — see PullRequestMergedEvent.
        events.publishEvent(new PullRequestMergedEvent(pr.getRepository().getId(), owner, repoName,
                pr.getNumber(), actor, intoDefaultBranch,
                closingKeywordParser.closedIssueNumbers(pr.getTitle(), pr.getDescription())));

        return new MergeResultDto(true, newTip, method.wireName(), "Pull request successfully merged");
    }

    /** Commit identity for a user; falls back to a no-reply address so git never sees a null email. */
    private static PersonIdent identityOf(User user) {
        return new PersonIdent(
                user.getName() != null ? user.getName() : "gitbase",
                user.getEmail() != null ? user.getEmail() : "noreply@gitbase.local");
    }

    // ────────────────────────────── close / reopen ──────────────────────────────

    @Override
    @Transactional
    public PullRequestDto close(String owner, String repoName, int number, String actor, boolean hasWriteAccess) {
        PullRequest pr = requirePullRequest(owner, repoName, number);
        requireOpen(pr, "close");
        requireAuthorOrWriter(pr, actor, hasWriteAccess, "close");

        pr.setStatus(PullRequestStatus.CLOSED);
        pr.setClosedAt(LocalDateTime.now());
        pullRequestRepository.save(pr);

        activityService.logActivity(ActivityType.PULL_REQUEST, actor, repoName,
                "[ " + repoName + " ] Closed pull request #" + pr.getNumber());

        return toDto(pr, owner, repoName);
    }

    @Override
    @Transactional
    public PullRequestDto reopen(String owner, String repoName, int number, String actor, boolean hasWriteAccess) {
        PullRequest pr = requirePullRequest(owner, repoName, number);
        if (pr.getStatus() != PullRequestStatus.CLOSED) {
            throw new InvalidStateException(
                    "Only a closed pull request can be reopened (this one is " + pr.getStatus() + ")");
        }
        requireAuthorOrWriter(pr, actor, hasWriteAccess, "reopen");

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

    /** Closing/reopening is limited to the PR author and users with write access. */
    private void requireAuthorOrWriter(PullRequest pr, String actor, boolean hasWriteAccess, String action) {
        boolean isAuthor = pr.getAuthor() != null && actor.equals(pr.getAuthor().getName());
        if (!isAuthor && !hasWriteAccess) {
            throw new AccessDeniedDomainException(
                    "Only the pull request author or a user with write access can " + action + " it");
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
        String currentHeadSha = null;
        List<Integer> closingIssues = List.of();
        if (pr.getStatus() == PullRequestStatus.OPEN) {
            Optional<CompareDto> comparison = safeCompare(owner, repoName, pr);
            if (comparison.isPresent()) {
                commitsCount = comparison.get().commits().size();
                changedFiles = comparison.get().files().size();
            }
            LiveRefs refs = liveRefs(owner, repoName, pr.getSourceBranch());
            currentHeadSha = refs.sourceTip();
            // Only a merge into the default branch closes issues, so only then is it worth saying.
            if (pr.getTargetBranch().equals(refs.defaultBranch())) {
                closingIssues = closingIssueResolver.closingIssues(pr.getRepository(), pr.getTitle(), pr.getDescription());
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
                currentHeadSha,
                closingIssues,
                toUserSummary(pr.getAuthor()),
                toUserSummary(pr.getMergedBy()),
                pr.getMergeCommitSha(),
                mergeMethodOf(pr),
                commitsCount,
                changedFiles,
                commentRepository.countByPullRequest(pr),
                toEpochMillis(pr.getCreatedAt()),
                toEpochMillis(pr.getUpdatedAt()),
                toEpochMillis(pr.getMergedAt()),
                toEpochMillis(pr.getClosedAt()));
    }

    /** Live source tip and default branch; either may be null. */
    private record LiveRefs(String sourceTip, String defaultBranch) {
    }

    /** Read in one repository open; a detail read must never fail over these. */
    private LiveRefs liveRefs(String owner, String repoName, String sourceBranch) {
        try (Repository repo = openRepository(owner, repoName)) {
            ObjectId tip = repo.resolve(Constants.R_HEADS + sourceBranch);
            return new LiveRefs(tip == null ? null : tip.getName(), defaultBranchOf(repo));
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read refs of {}/{}: {}", owner, repoName, e.getMessage());
            return new LiveRefs(null, null);
        }
    }

    /** Rows merged before merge methods existed were all merge commits. */
    private static String mergeMethodOf(PullRequest pr) {
        if (pr.getStatus() != PullRequestStatus.MERGED) {
            return null;
        }
        return (pr.getMergeMethod() != null ? pr.getMergeMethod() : MergeMethod.MERGE).wireName();
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

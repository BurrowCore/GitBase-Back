package org.os.gitbase.git.service;

import lombok.extern.slf4j.Slf4j;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.exception.AccessDeniedDomainException;
import org.os.gitbase.exception.InvalidStateException;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.git.dto.*;
import org.os.gitbase.git.entity.Issue;
import org.os.gitbase.git.entity.IssueComment;
import org.os.gitbase.git.entity.Label;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.entity.enums.ActivityType;
import org.os.gitbase.git.entity.enums.IssueState;
import org.os.gitbase.git.mapper.IssueMapper;
import org.os.gitbase.git.mapper.LabelMapper;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.os.gitbase.git.repository.IssueCommentRepository;
import org.os.gitbase.git.repository.IssueRepository;
import org.os.gitbase.git.repository.LabelRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class IssueServiceImpl implements IssueService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 30;
    private static final String DEFAULT_LABEL_COLOR = "cccccc";

    private final IssueRepository issueRepository;
    private final IssueCommentRepository commentRepository;
    private final LabelRepository labelRepository;
    private final GitRepositoryDB repositoryDB;
    private final UserRepository userRepository;
    private final IssueNumberAllocator numberAllocator;
    private final ActivityService activityService;
    private final IssueMapper issueMapper;
    private final LabelMapper labelMapper;

    public IssueServiceImpl(IssueRepository issueRepository,
                            IssueCommentRepository commentRepository,
                            LabelRepository labelRepository,
                            GitRepositoryDB repositoryDB,
                            UserRepository userRepository,
                            IssueNumberAllocator numberAllocator,
                            ActivityService activityService,
                            IssueMapper issueMapper,
                            LabelMapper labelMapper) {
        this.issueRepository = issueRepository;
        this.commentRepository = commentRepository;
        this.labelRepository = labelRepository;
        this.repositoryDB = repositoryDB;
        this.userRepository = userRepository;
        this.numberAllocator = numberAllocator;
        this.activityService = activityService;
        this.issueMapper = issueMapper;
        this.labelMapper = labelMapper;
    }

    // ────────────────────────── create ──────────────────────────

    @Override
    @Transactional
    public IssueDto create(String owner, String repoName, String actor, CreateIssueDto request) {
        RepositoryGit repo = requireRepository(owner, repoName);

        Issue issue = new Issue();
        issue.setRepository(repo);
        // Shared sequence with pull requests, so #numbers never collide across the two.
        issue.setNumber(numberAllocator.allocate(repo));
        issue.setTitle(request.title().trim());
        issue.setBody(request.body());
        issue.setAuthor(requireUser(actor));
        issue.setState(IssueState.OPEN);
        issue.setLabels(resolveLabels(repo, request.labels()));
        issue.setAssignees(resolveAssignees(request.assignees()));

        Issue saved = issueRepository.save(issue);

        activityService.logActivity(ActivityType.REPO_UPDATED, actor, repoName,
                "[ " + repoName + " ] Opened issue #" + saved.getNumber() + ": " + saved.getTitle());

        return toDto(saved);
    }

    // ────────────────────────── read ──────────────────────────

    @Override
    @Transactional(readOnly = true)
    public IssuePageDto list(String owner, String repoName, String state, String label,
                             String assignee, String search, int page, int size) {
        RepositoryGit repo = requireRepository(owner, repoName);

        int safeSize = Math.clamp(size <= 0 ? DEFAULT_PAGE_SIZE : size, 1, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "number"));

        Page<Issue> result = issueRepository.search(
                repo, parseState(state), blankToNull(label), blankToNull(assignee), blankToNull(search), pageable);

        List<IssueSummaryDto> rows = result.getContent().stream()
                .map(issue -> issueMapper.toSummary(issue, commentRepository.countByIssue(issue)))
                .toList();

        return new IssuePageDto(rows, safePage, safeSize, result.getTotalElements(), result.getTotalPages(),
                result.hasNext(),
                issueRepository.countByRepositoryAndState(repo, IssueState.OPEN),
                issueRepository.countByRepositoryAndState(repo, IssueState.CLOSED));
    }

    @Override
    @Transactional(readOnly = true)
    public IssueDto get(String owner, String repoName, int number) {
        return toDto(requireIssue(owner, repoName, number));
    }

    // ────────────────────────── update / state ──────────────────────────

    @Override
    @Transactional
    public IssueDto update(String owner, String repoName, int number, String actor,
                           UpdateIssueDto request, boolean actorHasWriteAccess) {
        Issue issue = requireIssue(owner, repoName, number);
        requireAuthorOrWriter(issue, actor, actorHasWriteAccess, "edit");

        if (request.title() != null) {
            String title = request.title().trim();
            if (title.isEmpty()) {
                throw new IllegalArgumentException("Title cannot be blank");
            }
            issue.setTitle(title);
        }
        if (request.body() != null) {
            issue.setBody(request.body());
        }
        // A null list means "leave unchanged"; an empty list means "clear them".
        if (request.labels() != null) {
            issue.setLabels(resolveLabels(issue.getRepository(), request.labels()));
        }
        if (request.assignees() != null) {
            issue.setAssignees(resolveAssignees(request.assignees()));
        }

        return toDto(issueRepository.save(issue));
    }

    @Override
    @Transactional
    public IssueDto close(String owner, String repoName, int number, String actor, boolean actorHasWriteAccess) {
        Issue issue = requireIssue(owner, repoName, number);
        requireAuthorOrWriter(issue, actor, actorHasWriteAccess, "close");
        if (issue.getState() == IssueState.CLOSED) {
            throw new InvalidStateException("Issue #" + number + " is already closed");
        }

        issue.setState(IssueState.CLOSED);
        issue.setClosedAt(LocalDateTime.now());
        issue.setClosedBy(requireUser(actor));

        activityService.logActivity(ActivityType.REPO_UPDATED, actor, repoName,
                "[ " + repoName + " ] Closed issue #" + number);

        return toDto(issueRepository.save(issue));
    }

    @Override
    @Transactional
    public IssueDto reopen(String owner, String repoName, int number, String actor, boolean actorHasWriteAccess) {
        Issue issue = requireIssue(owner, repoName, number);
        requireAuthorOrWriter(issue, actor, actorHasWriteAccess, "reopen");
        if (issue.getState() == IssueState.OPEN) {
            throw new InvalidStateException("Issue #" + number + " is already open");
        }

        issue.setState(IssueState.OPEN);
        issue.setClosedAt(null);
        issue.setClosedBy(null);

        activityService.logActivity(ActivityType.REPO_UPDATED, actor, repoName,
                "[ " + repoName + " ] Reopened issue #" + number);

        return toDto(issueRepository.save(issue));
    }

    // ────────────────────────── comments ──────────────────────────

    @Override
    @Transactional(readOnly = true)
    public CommentPageDto listComments(String owner, String repoName, int number, int page, int size) {
        Issue issue = requireIssue(owner, repoName, number);

        int safeSize = Math.clamp(size <= 0 ? DEFAULT_PAGE_SIZE : size, 1, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.ASC, "createdAt"));

        Page<IssueComment> result = commentRepository.findByIssue(issue, pageable);
        List<CommentDto> comments = result.getContent().stream().map(issueMapper::toCommentDto).toList();

        return new CommentPageDto(comments, safePage, safeSize, result.getTotalElements(), result.hasNext());
    }

    @Override
    @Transactional
    public CommentDto addComment(String owner, String repoName, int number, String actor, CreateCommentDto request) {
        Issue issue = requireIssue(owner, repoName, number);

        IssueComment comment = new IssueComment();
        comment.setIssue(issue);
        comment.setAuthor(requireUser(actor));
        comment.setBody(request.body().trim());

        return issueMapper.toCommentDto(commentRepository.save(comment));
    }

    // ────────────────────────── labels ──────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<LabelDto> listLabels(String owner, String repoName) {
        RepositoryGit repo = requireRepository(owner, repoName);
        return labelMapper.toDtoList(labelRepository.findByRepositoryOrderByNameAsc(repo));
    }

    @Override
    @Transactional
    public LabelDto saveLabel(String owner, String repoName, CreateLabelDto request) {
        RepositoryGit repo = requireRepository(owner, repoName);
        String name = request.name().trim();

        // Upsert, so re-adding an existing label recolours it instead of failing.
        Label label = labelRepository.findByRepositoryAndName(repo, name)
                .orElseGet(() -> {
                    Label fresh = new Label();
                    fresh.setRepository(repo);
                    fresh.setName(name);
                    return fresh;
                });
        label.setColor(StringUtils.hasText(request.color()) ? request.color().toLowerCase() : DEFAULT_LABEL_COLOR);
        label.setDescription(request.description());

        return labelMapper.toDto(labelRepository.save(label));
    }

    @Override
    @Transactional
    public void deleteLabel(String owner, String repoName, String name) {
        RepositoryGit repo = requireRepository(owner, repoName);
        Label label = labelRepository.findByRepositoryAndName(repo, name == null ? "" : name.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Label not found: " + name));
        // The issue_labels join rows go with it; the issues themselves are untouched.
        labelRepository.delete(label);
    }

    // ────────────────────────── helpers ──────────────────────────

    private IssueDto toDto(Issue issue) {
        return issueMapper.toDto(issue, commentRepository.countByIssue(issue));
    }

    /**
     * Resolves label names to entities, rejecting unknown ones so a typo cannot silently drop a
     * label the caller believes they set. One query for the whole set, not one per name.
     */
    private Set<Label> resolveLabels(RepositoryGit repo, List<String> names) {
        if (names == null || names.isEmpty()) {
            return new LinkedHashSet<>();
        }
        List<String> wanted = names.stream()
                .filter(StringUtils::hasText).map(String::trim).distinct().toList();

        List<Label> found = labelRepository.findByRepositoryAndNameIn(repo, wanted);
        if (found.size() != wanted.size()) {
            Set<String> resolved = found.stream().map(Label::getName).collect(Collectors.toSet());
            String missing = wanted.stream().filter(n -> !resolved.contains(n)).collect(Collectors.joining(", "));
            throw new ResourceNotFoundException("Unknown label(s): " + missing);
        }
        return new LinkedHashSet<>(found);
    }

    private Set<User> resolveAssignees(List<String> usernames) {
        Set<User> assignees = new LinkedHashSet<>();
        if (usernames == null) {
            return assignees;
        }
        for (String username : usernames) {
            if (StringUtils.hasText(username)) {
                assignees.add(requireUser(username.trim()));
            }
        }
        return assignees;
    }

    private IssueState parseState(String state) {
        if (!StringUtils.hasText(state) || state.equalsIgnoreCase("all")) {
            return null;
        }
        try {
            return IssueState.valueOf(state.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown state '" + state + "' (expected OPEN, CLOSED or ALL)");
        }
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private RepositoryGit requireRepository(String owner, String repoName) {
        return repositoryDB.findByOwnerNameAndRepoName(owner, repoName)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Repository not found: " + owner + "/" + repoName));
    }

    private Issue requireIssue(String owner, String repoName, int number) {
        RepositoryGit repo = requireRepository(owner, repoName);
        return issueRepository.findByRepositoryAndNumber(repo, number)
                .orElseThrow(() -> new ResourceNotFoundException("Issue #" + number + " not found"));
    }

    private User requireUser(String username) {
        return userRepository.findUserByName(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
    }

    /**
     * Editing an issue is limited to its author and anyone with write access — the rule Gitea
     * applies. Write access is resolved by the controller (it needs the Principal) and passed in.
     */
    private void requireAuthorOrWriter(Issue issue, String actor, boolean actorHasWriteAccess, String action) {
        boolean isAuthor = issue.getAuthor() != null && actor.equals(issue.getAuthor().getName());
        if (!isAuthor && !actorHasWriteAccess) {
            throw new AccessDeniedDomainException(
                    "Only the issue author or a user with write access can " + action + " it");
        }
    }
}

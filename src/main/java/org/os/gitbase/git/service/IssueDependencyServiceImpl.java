package org.os.gitbase.git.service;

import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.exception.AccessDeniedDomainException;
import org.os.gitbase.exception.ConflictException;
import org.os.gitbase.exception.InvalidStateException;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.os.gitbase.git.dto.IssueRefDto;
import org.os.gitbase.git.dto.IssueRefPageDto;
import org.os.gitbase.git.entity.Issue;
import org.os.gitbase.git.entity.IssueDependency;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.mapper.IssueMapper;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.os.gitbase.git.repository.IssueDependencyRepository;
import org.os.gitbase.git.repository.IssueRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class IssueDependencyServiceImpl implements IssueDependencyService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 30;

    /**
     * Upper bound on issues visited by the cycle check (ADR-004). Any cycle is rejected, not just
     * the direct A↔B pair — but past this many issues the request is refused rather than walk an
     * unbounded graph.
     */
    static final int MAX_CYCLE_CHECK_NODES = 1000;

    private final IssueDependencyRepository dependencyRepository;
    private final IssueRepository issueRepository;
    private final GitRepositoryDB repositoryDB;
    private final UserRepository userRepository;
    private final IssueMapper issueMapper;

    public IssueDependencyServiceImpl(IssueDependencyRepository dependencyRepository,
                                      IssueRepository issueRepository,
                                      GitRepositoryDB repositoryDB,
                                      UserRepository userRepository,
                                      IssueMapper issueMapper) {
        this.dependencyRepository = dependencyRepository;
        this.issueRepository = issueRepository;
        this.repositoryDB = repositoryDB;
        this.userRepository = userRepository;
        this.issueMapper = issueMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public IssueRefPageDto dependencies(String owner, String repoName, int number, int page, int size) {
        Issue issue = requireIssue(requireRepository(owner, repoName), number);
        return toPage(dependencyRepository.findDependenciesOf(issue, pageRequest(page, size)));
    }

    @Override
    @Transactional(readOnly = true)
    public IssueRefPageDto dependents(String owner, String repoName, int number, int page, int size) {
        Issue issue = requireIssue(requireRepository(owner, repoName), number);
        return toPage(dependencyRepository.findDependentsOf(issue, pageRequest(page, size)));
    }

    @Override
    @Transactional
    public IssueRefDto addDependency(String owner, String repoName, int number, int dependencyNumber,
                                     String actor, boolean actorHasWriteAccess) {
        if (number == dependencyNumber) {
            throw new IllegalArgumentException("An issue cannot depend on itself");
        }
        requireWriter(actorHasWriteAccess);
        RepositoryGit repo = requireRepository(owner, repoName);
        Issue issue = requireIssue(repo, number);
        Issue dependency = issueRepository.findByRepositoryAndNumber(repo, dependencyNumber)
                // Numbers are shared with pull requests, so "not an issue" is a bad request, not a 404.
                .orElseThrow(() -> new IllegalArgumentException("#" + dependencyNumber + " is not an issue in this repository"));

        // Lock both rows (lowest id first, so two requests cannot deadlock) — otherwise concurrent
        // "A depends on B" and "B depends on A" could each pass the cycle check below.
        issueRepository.lockForUpdate(Math.min(issue.getId(), dependency.getId()));
        issueRepository.lockForUpdate(Math.max(issue.getId(), dependency.getId()));

        if (dependencyRepository.existsByIssueAndDependency(issue, dependency)) {
            throw new ConflictException("#" + number + " already depends on #" + dependencyNumber);
        }
        if (wouldCreateCycle(issue, dependency)) {
            throw new ConflictException("#" + dependencyNumber + " already depends on #" + number
                    + " (directly or indirectly) — that would be a circular dependency");
        }

        IssueDependency link = new IssueDependency();
        link.setIssue(issue);
        link.setDependency(dependency);
        link.setCreatedBy(requireUser(actor));
        dependencyRepository.save(link);
        return issueMapper.toRef(dependency);
    }

    @Override
    @Transactional
    public void removeDependency(String owner, String repoName, int number, int dependencyNumber,
                                 String actor, boolean actorHasWriteAccess) {
        requireWriter(actorHasWriteAccess);
        RepositoryGit repo = requireRepository(owner, repoName);
        Issue issue = requireIssue(repo, number);
        Issue dependency = requireIssue(repo, dependencyNumber);
        IssueDependency link = dependencyRepository.findByIssueAndDependency(issue, dependency)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "#" + number + " does not depend on #" + dependencyNumber));
        dependencyRepository.delete(link);
    }

    /**
     * Adding "issue depends on dependency" closes a cycle iff {@code issue} is already reachable
     * from {@code dependency} by following depends-on links. Breadth-first, one query per level.
     */
    private boolean wouldCreateCycle(Issue issue, Issue dependency) {
        Set<Long> visited = new HashSet<>();
        Set<Long> frontier = Set.of(dependency.getId());
        while (!frontier.isEmpty()) {
            if (frontier.contains(issue.getId())) {
                return true;
            }
            visited.addAll(frontier);
            if (visited.size() > MAX_CYCLE_CHECK_NODES) {
                throw new InvalidStateException("Dependency graph is too large to check for cycles");
            }
            Set<Long> next = new HashSet<>(dependencyRepository.findDependencyIdsOf(frontier));
            next.removeAll(visited);
            frontier = next;
        }
        return false;
    }

    private IssueRefPageDto toPage(Page<Issue> page) {
        List<IssueRefDto> refs = page.getContent().stream().map(issueMapper::toRef).toList();
        return new IssueRefPageDto(refs, page.getNumber(), page.getSize(), page.getTotalElements(), page.hasNext());
    }

    private static PageRequest pageRequest(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size <= 0 ? DEFAULT_PAGE_SIZE : size, 1, MAX_PAGE_SIZE));
    }

    private RepositoryGit requireRepository(String owner, String repoName) {
        return repositoryDB.findByOwnerNameAndRepoName(owner, repoName)
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found: " + owner + "/" + repoName));
    }

    private Issue requireIssue(RepositoryGit repo, int number) {
        return issueRepository.findByRepositoryAndNumber(repo, number)
                .orElseThrow(() -> new ResourceNotFoundException("Issue #" + number + " not found"));
    }

    private User requireUser(String username) {
        return userRepository.findUserByName(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
    }

    /**
     * Write access only. Being
     * the issue's author is deliberately not enough: on a public repository a read-only user could
     * otherwise link their own issue to every maintainer issue and fill its "Blocks" list.
     */
    private void requireWriter(boolean actorHasWriteAccess) {
        if (!actorHasWriteAccess) {
            throw new AccessDeniedDomainException("Only users with write access can change issue dependencies");
        }
    }
}

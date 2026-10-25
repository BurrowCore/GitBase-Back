package org.os.gitbase.git.service;

import org.junit.jupiter.api.Test;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.exception.InvalidStateException;
import org.os.gitbase.git.entity.Issue;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.mapper.IssueMapper;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.os.gitbase.git.repository.IssueDependencyRepository;
import org.os.gitbase.git.repository.IssueRepository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The cycle check refuses rather than walk an unbounded dependency graph (ADR-004). */
class IssueDependencyServiceImplTest {

    @Test
    void cycleCheckGivesUpPastTheNodeCap() {
        IssueDependencyRepository deps = mock(IssueDependencyRepository.class);
        IssueRepository issues = mock(IssueRepository.class);
        GitRepositoryDB repos = mock(GitRepositoryDB.class);
        RepositoryGit repo = new RepositoryGit();
        when(repos.findByOwnerNameAndRepoName("alice", "demo")).thenReturn(Optional.of(repo));
        when(issues.findByRepositoryAndNumber(eq(repo), anyInt())).thenAnswer(inv -> {
            Issue issue = new Issue();
            issue.setId((long) inv.<Integer>getArgument(1));
            issue.setNumber(inv.getArgument(1));
            return Optional.of(issue);
        });
        when(issues.lockForUpdate(anyLong())).thenReturn(Optional.of(1L));
        // Every level of the walk discovers 600 brand-new issues — a pathological graph.
        AtomicLong next = new AtomicLong(1_000);
        when(deps.findDependencyIdsOf(any())).thenAnswer(inv ->
                LongStream.range(0, 600).map(i -> next.getAndIncrement()).boxed().toList());

        var service = new IssueDependencyServiceImpl(deps, issues, repos, mock(UserRepository.class), mock(IssueMapper.class));

        assertThatThrownBy(() -> service.addDependency("alice", "demo", 1, 2, "alice", true))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void writeAccessIsCheckedBeforeAnythingIsLoaded() {
        GitRepositoryDB repos = mock(GitRepositoryDB.class);
        var service = new IssueDependencyServiceImpl(mock(IssueDependencyRepository.class), mock(IssueRepository.class),
                repos, mock(UserRepository.class), mock(IssueMapper.class));

        assertThatThrownBy(() -> service.addDependency("alice", "demo", 1, 2, "mallory", false))
                .isInstanceOf(org.os.gitbase.exception.AccessDeniedDomainException.class);
        org.mockito.Mockito.verifyNoInteractions(repos);
        List.of(); // keep imports tidy for future cases
    }
}

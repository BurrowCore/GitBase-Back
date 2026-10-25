package org.os.gitbase.git.repository;

import org.os.gitbase.git.entity.RepositoryGit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Removes everything that references a repository row, so the row itself can be deleted.
 *
 * <p>None of these foreign keys cascade, so deleting a repository that had an issue, a pull
 * request, a label, a collaborator or a team grant used to fail with a constraint violation.
 * Native bulk deletes — a fixed number of statements however much the repository holds, and no
 * entities loaded (ADR-004). Join tables ({@code issue_labels}, {@code issue_assignees}) are not
 * entities, which is why this is SQL rather than JPQL. Must run inside a transaction.
 */
public interface RepositoryPurgeRepository extends org.springframework.data.repository.Repository<RepositoryGit, UUID> {

    String ISSUES_OF = "(SELECT id FROM issues WHERE repository_id = :repositoryId)";

    // Flush pending changes before each bulk delete, and clear the persistence context after it:
    // SQL deletes bypass Hibernate, so an already-loaded issue or pull request would otherwise stay
    // managed and make the later flush fail on the deleted repository.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = "DELETE FROM issue_dependencies WHERE issue_id IN " + ISSUES_OF
            + " OR dependency_id IN " + ISSUES_OF)
    void deleteIssueDependencies(@Param("repositoryId") UUID repositoryId);

    // Flush pending changes before each bulk delete, and clear the persistence context after it:
    // SQL deletes bypass Hibernate, so an already-loaded issue or pull request would otherwise stay
    // managed and make the later flush fail on the deleted repository.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = "DELETE FROM issue_labels WHERE issue_id IN " + ISSUES_OF)
    void deleteIssueLabels(@Param("repositoryId") UUID repositoryId);

    // Flush pending changes before each bulk delete, and clear the persistence context after it:
    // SQL deletes bypass Hibernate, so an already-loaded issue or pull request would otherwise stay
    // managed and make the later flush fail on the deleted repository.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = "DELETE FROM issue_assignees WHERE issue_id IN " + ISSUES_OF)
    void deleteIssueAssignees(@Param("repositoryId") UUID repositoryId);

    // Flush pending changes before each bulk delete, and clear the persistence context after it:
    // SQL deletes bypass Hibernate, so an already-loaded issue or pull request would otherwise stay
    // managed and make the later flush fail on the deleted repository.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = "DELETE FROM issue_comments WHERE issue_id IN " + ISSUES_OF)
    void deleteIssueComments(@Param("repositoryId") UUID repositoryId);

    // Flush pending changes before each bulk delete, and clear the persistence context after it:
    // SQL deletes bypass Hibernate, so an already-loaded issue or pull request would otherwise stay
    // managed and make the later flush fail on the deleted repository.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = "DELETE FROM issues WHERE repository_id = :repositoryId")
    void deleteIssues(@Param("repositoryId") UUID repositoryId);

    // Flush pending changes before each bulk delete, and clear the persistence context after it:
    // SQL deletes bypass Hibernate, so an already-loaded issue or pull request would otherwise stay
    // managed and make the later flush fail on the deleted repository.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = "DELETE FROM labels WHERE repository_id = :repositoryId")
    void deleteLabels(@Param("repositoryId") UUID repositoryId);

    // Flush pending changes before each bulk delete, and clear the persistence context after it:
    // SQL deletes bypass Hibernate, so an already-loaded issue or pull request would otherwise stay
    // managed and make the later flush fail on the deleted repository.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = "DELETE FROM pull_request_comments WHERE pull_request_id IN "
            + "(SELECT id FROM pull_requests WHERE repository_id = :repositoryId)")
    void deletePullRequestComments(@Param("repositoryId") UUID repositoryId);

    // Flush pending changes before each bulk delete, and clear the persistence context after it:
    // SQL deletes bypass Hibernate, so an already-loaded issue or pull request would otherwise stay
    // managed and make the later flush fail on the deleted repository.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = "DELETE FROM pull_requests WHERE repository_id = :repositoryId")
    void deletePullRequests(@Param("repositoryId") UUID repositoryId);

    // Flush pending changes before each bulk delete, and clear the persistence context after it:
    // SQL deletes bypass Hibernate, so an already-loaded issue or pull request would otherwise stay
    // managed and make the later flush fail on the deleted repository.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = "DELETE FROM repository_collaborators WHERE repository_id = :repositoryId")
    void deleteCollaborators(@Param("repositoryId") UUID repositoryId);

    // Flush pending changes before each bulk delete, and clear the persistence context after it:
    // SQL deletes bypass Hibernate, so an already-loaded issue or pull request would otherwise stay
    // managed and make the later flush fail on the deleted repository.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = "DELETE FROM team_repositories WHERE repository_id = :repositoryId")
    void deleteTeamGrants(@Param("repositoryId") UUID repositoryId);

    /** Children before parents. */
    default void purgeChildrenOf(UUID repositoryId) {
        deleteIssueDependencies(repositoryId);
        deleteIssueLabels(repositoryId);
        deleteIssueAssignees(repositoryId);
        deleteIssueComments(repositoryId);
        deleteIssues(repositoryId);
        deleteLabels(repositoryId);
        deletePullRequestComments(repositoryId);
        deletePullRequests(repositoryId);
        deleteCollaborators(repositoryId);
        deleteTeamGrants(repositoryId);
    }
}

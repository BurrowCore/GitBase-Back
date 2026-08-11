package org.os.gitbase.git.repository;

import org.os.gitbase.auth.entity.User;
import org.os.gitbase.git.entity.Collaborator;
import org.os.gitbase.git.entity.RepositoryGit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface CollaboratorRepository extends JpaRepository<Collaborator, UUID> {

    /**
     * Effective grant for one user on one repository. This runs on every access check, so it is
     * backed by the unique {@code (repository_id, user_id)} index — an O(1) lookup (ADR-004).
     */
    @Query("SELECT c FROM Collaborator c WHERE c.repository = :repository AND c.user.name = :username")
    Optional<Collaborator> findByRepositoryAndUsername(@Param("repository") RepositoryGit repository,
                                                       @Param("username") String username);

    Optional<Collaborator> findByRepositoryAndUser(RepositoryGit repository, User user);

    /** JOIN FETCH the user: the listing renders usernames, so avoid an N+1 on the lazy association. */
    @Query("SELECT c FROM Collaborator c JOIN FETCH c.user WHERE c.repository = :repository ORDER BY c.createdAt ASC")
    List<Collaborator> findByRepository(@Param("repository") RepositoryGit repository);

    void deleteByRepositoryAndUser(RepositoryGit repository, User user);

    /**
     * Names of repositories in {@code owner}'s namespace that {@code username} collaborates on.
     * One query, so the repository listing can include private repos the caller has a grant on
     * without an N+1 permission check per row.
     */
    @Query("SELECT c.repository.repoName FROM Collaborator c "
            + "WHERE c.user.name = :username AND c.repository.owner.name = :owner")
    List<String> findCollaboratingRepoNames(@Param("owner") String owner, @Param("username") String username);
}

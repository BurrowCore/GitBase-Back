package org.os.gitbase.git.repository;

import org.os.gitbase.git.entity.Commit;
import org.os.gitbase.git.entity.RepositoryGit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommitRepository extends JpaRepository<Commit, UUID> {
    Optional<Commit> findByCommitHash(String commitHash);

    List<Commit> findByRepositoryOrderByCommittedAtDesc(RepositoryGit repository);
}


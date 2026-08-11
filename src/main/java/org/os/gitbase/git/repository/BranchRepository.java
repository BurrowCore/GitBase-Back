package org.os.gitbase.git.repository;

import org.os.gitbase.git.entity.Branch;
import org.os.gitbase.git.entity.RepositoryGit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BranchRepository extends JpaRepository<Branch, UUID> {
    Optional<Branch> findByNameAndRepository(String name, RepositoryGit repository);
    List<Branch> findByRepository(RepositoryGit repository);
}


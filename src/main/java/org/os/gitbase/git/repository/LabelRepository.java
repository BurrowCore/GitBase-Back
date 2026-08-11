package org.os.gitbase.git.repository;

import org.os.gitbase.git.entity.Label;
import org.os.gitbase.git.entity.RepositoryGit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@org.springframework.stereotype.Repository
public interface LabelRepository extends JpaRepository<Label, Long> {

    List<Label> findByRepositoryOrderByNameAsc(RepositoryGit repository);

    Optional<Label> findByRepositoryAndName(RepositoryGit repository, String name);

    /** Resolves a set of label names in one query, rather than one lookup per name. */
    List<Label> findByRepositoryAndNameIn(RepositoryGit repository, Collection<String> names);
}

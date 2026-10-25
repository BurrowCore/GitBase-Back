package org.os.gitbase.git.repository;

import org.os.gitbase.auth.entity.User;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.entity.Team;
import org.os.gitbase.git.entity.TeamRepo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TeamRepoRepository extends JpaRepository<TeamRepo, Long> {

    boolean existsByTeamAndRepository(Team team, RepositoryGit repository);

    Optional<TeamRepo> findByTeamAndRepository(Team team, RepositoryGit repository);

    long countByTeam(Team team);

    @Query(value = "SELECT tr.repository FROM TeamRepo tr WHERE tr.team = :team ORDER BY tr.repository.repoName",
            countQuery = "SELECT COUNT(tr) FROM TeamRepo tr WHERE tr.team = :team")
    Page<RepositoryGit> findRepositoriesOfTeam(@Param("team") Team team, Pageable pageable);

    /** Names of the repositories explicitly granted to any of {@code teams} — for private listings. */
    @Query("SELECT DISTINCT tr.repository.repoName FROM TeamRepo tr WHERE tr.team IN :teams")
    List<String> findRepoNamesOfTeams(@Param("teams") Collection<Team> teams);

    @Modifying
    @Query("DELETE FROM TeamRepo tr WHERE tr.repository = :repository")
    void deleteByRepository(@Param("repository") RepositoryGit repository);

    @Modifying
    @Query("DELETE FROM TeamRepo tr WHERE tr.team = :team")
    void deleteByTeam(@Param("team") Team team);

    @Modifying
    @Query("DELETE FROM TeamRepo tr WHERE tr.team.organization = :org")
    void deleteByOrganization(@Param("org") User organization);
}

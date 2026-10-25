package org.os.gitbase.git.repository;

import org.os.gitbase.auth.entity.User;
import org.os.gitbase.git.entity.Team;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {

    Optional<Team> findByOrganizationAndLowerName(User organization, String lowerName);

    boolean existsByOrganizationAndLowerName(User organization, String lowerName);

    @Query(value = "SELECT t FROM Team t WHERE t.organization = :org ORDER BY t.ownerTeam DESC, t.lowerName",
            countQuery = "SELECT COUNT(t) FROM Team t WHERE t.organization = :org")
    Page<Team> findByOrganization(@Param("org") User organization, Pageable pageable);

    Optional<Team> findByOrganizationAndOwnerTeamTrue(User organization);

    /**
     * What {@code username}'s teams in organization {@code orgName} grant on one repository: the
     * permission of every matching team, flagged when it is the Owners team. Empty for a
     * personal namespace (users have no teams). One indexed query — run on every permission check.
     */
    @Query("SELECT new org.os.gitbase.git.repository.TeamGrant(t.permission, t.ownerTeam) "
            + "FROM TeamMember m JOIN m.team t "
            + "WHERE t.organization.name = :orgName AND m.user.name = :username "
            + "AND (t.ownerTeam = true OR t.includesAllRepositories = true "
            + "     OR EXISTS (SELECT 1 FROM TeamRepo tr WHERE tr.team = t AND tr.repository.id = :repositoryId))")
    List<TeamGrant> findGrants(@Param("orgName") String orgName, @Param("username") String username,
                               @Param("repositoryId") java.util.UUID repositoryId);

    /**
     * {@code username}'s teams in organization {@code orgName}, for deciding which private
     * repositories to list and whether they may create repositories there.
     */
    @Query("SELECT t FROM TeamMember m JOIN m.team t WHERE t.organization.name = :orgName AND m.user.name = :username")
    List<Team> findTeamsOfMember(@Param("orgName") String orgName, @Param("username") String username);

    @Modifying
    @Query("DELETE FROM Team t WHERE t.organization = :org")
    void deleteByOrganization(@Param("org") User organization);
}

package org.os.gitbase.git.repository;

import org.os.gitbase.auth.entity.User;
import org.os.gitbase.git.entity.Team;
import org.os.gitbase.git.entity.TeamMember;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {

    boolean existsByTeamAndUser(Team team, User user);

    Optional<TeamMember> findByTeamAndUser(Team team, User user);

    long countByTeam(Team team);

    @Query(value = "SELECT m.user FROM TeamMember m WHERE m.team = :team ORDER BY m.user.name",
            countQuery = "SELECT COUNT(m) FROM TeamMember m WHERE m.team = :team")
    Page<User> findUsersOfTeam(@Param("team") Team team, Pageable pageable);

    /** Organization members: everyone in at least one of its teams, once each. */
    @Query(value = "SELECT DISTINCT m.user FROM TeamMember m WHERE m.team.organization = :org ORDER BY m.user.name",
            countQuery = "SELECT COUNT(DISTINCT m.user) FROM TeamMember m WHERE m.team.organization = :org")
    Page<User> findMembersOfOrganization(@Param("org") User organization, Pageable pageable);

    /** Organizations {@code user} belongs to (through any team). */
    @Query(value = "SELECT DISTINCT m.team.organization FROM TeamMember m WHERE m.user = :user "
            + "ORDER BY m.team.organization.name",
            countQuery = "SELECT COUNT(DISTINCT m.team.organization) FROM TeamMember m WHERE m.user = :user")
    Page<User> findOrganizationsOf(@Param("user") User user, Pageable pageable);

    /** Member of organization {@code orgName} through any team. */
    boolean existsByTeam_Organization_NameAndUser_Name(String orgName, String username);

    /** Member of the Owners team of organization {@code orgName}. */
    boolean existsByTeam_Organization_NameAndTeam_OwnerTeamTrueAndUser_Name(String orgName, String username);

    default boolean isMember(String orgName, String username) {
        return existsByTeam_Organization_NameAndUser_Name(orgName, username);
    }

    default boolean isOwner(String orgName, String username) {
        return existsByTeam_Organization_NameAndTeam_OwnerTeamTrueAndUser_Name(orgName, username);
    }

    @Modifying
    @Query("DELETE FROM TeamMember m WHERE m.team.organization = :org AND m.user = :user")
    void deleteFromOrganization(@Param("org") User organization, @Param("user") User user);

    @Modifying
    @Query("DELETE FROM TeamMember m WHERE m.team = :team")
    void deleteByTeam(@Param("team") Team team);

    @Modifying
    @Query("DELETE FROM TeamMember m WHERE m.team.organization = :org")
    void deleteByOrganization(@Param("org") User organization);
}

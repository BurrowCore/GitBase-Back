package org.os.gitbase.git.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.os.gitbase.auth.entity.User;

import java.time.LocalDateTime;

/**
 * A user's membership of a team. Organization membership is simply membership of any of its
 * teams; there is no separate organization-membership table. (Per-member public/private
 * membership visibility is not supported yet.)
 */
@Entity
@Table(
        name = "team_members",
        uniqueConstraints = @UniqueConstraint(name = "uk_team_member", columnNames = {"team_id", "user_id"}),
        indexes = @Index(name = "idx_team_member_user", columnList = "user_id")
)
@Getter
@Setter
public class TeamMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}

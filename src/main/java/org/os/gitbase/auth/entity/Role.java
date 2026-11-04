package org.os.gitbase.auth.entity;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_role")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Role {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE) // t_role_seq; roles are seeded by V3__seed_default_roles.sql
    private Long id;
    @Column(name = "role_name", nullable = false, unique = true)
    private String roleName;
    @ManyToMany(mappedBy = "roles", fetch = FetchType.LAZY)
    @JsonIgnore
    @Builder.Default
    private Set<User> users = new HashSet<>();

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Role role = (Role) o;
        return Objects.equals(id, role.id) &&
                Objects.equals(roleName, role.roleName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, roleName);
    }
}


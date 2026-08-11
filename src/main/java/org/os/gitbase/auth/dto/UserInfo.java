package org.os.gitbase.auth.dto;
import java.util.Set;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.os.gitbase.auth.entity.Role;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class UserInfo {
    UUID id;
    String name;
    String fullName;
    String email;
    String profilePictureUrl;
    Set<Role> roles;
}

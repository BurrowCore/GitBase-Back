package org.os.gitbase.auth.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.os.gitbase.auth.dto.UserInfo;
import org.os.gitbase.auth.entity.Role;
import org.os.gitbase.auth.entity.User;

import java.util.Set;

@Mapper(componentModel = "spring")
public interface UserMapper {
    default Set<Role> mapAuthorities(Set<Role> roles) {
        return roles;
    }
    @Mappings({
            @Mapping(target = "id",source="id"),
            @Mapping(target = "name",source = "name"),
            @Mapping(target = "fullName",source = "fullName"),
            @Mapping(target = "email",source = "email"),
            @Mapping(target = "profilePictureUrl",source = "profilePictureUrl"),
    })
    UserInfo mapFromAuthUserToUserInfoResponse(User user);






}

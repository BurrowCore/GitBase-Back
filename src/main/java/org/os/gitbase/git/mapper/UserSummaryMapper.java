package org.os.gitbase.git.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.git.dto.UserSummaryDto;

import java.util.Collection;
import java.util.List;

/** Maps a {@link User} down to the minimal public identity embedded in other payloads. */
@Mapper(componentModel = "spring")
public interface UserSummaryMapper {

    @Mapping(target = "username", source = "name")
    @Mapping(target = "avatarUrl", source = "profilePictureUrl")
    UserSummaryDto toSummary(User user);

    List<UserSummaryDto> toSummaryList(Collection<User> users);
}

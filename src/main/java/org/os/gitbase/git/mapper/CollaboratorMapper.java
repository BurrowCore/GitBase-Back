package org.os.gitbase.git.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.os.gitbase.git.dto.CollaboratorDto;
import org.os.gitbase.git.entity.Collaborator;

import java.util.Collection;
import java.util.List;

@Mapper(componentModel = "spring", uses = {TemporalMapper.class, UserSummaryMapper.class})
public interface CollaboratorMapper {

    @Mapping(target = "user", source = "user")
    @Mapping(target = "permission", source = "permission")
    @Mapping(target = "createdAt", source = "createdAt")
    CollaboratorDto toDto(Collaborator collaborator);

    List<CollaboratorDto> toDtoList(Collection<Collaborator> collaborators);
}

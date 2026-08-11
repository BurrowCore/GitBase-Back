package org.os.gitbase.git.mapper;

import org.mapstruct.Mapper;
import org.os.gitbase.git.dto.LabelDto;
import org.os.gitbase.git.entity.Label;

import java.util.Collection;
import java.util.List;

@Mapper(componentModel = "spring")
public interface LabelMapper {

    LabelDto toDto(Label label);

    List<LabelDto> toDtoList(Collection<Label> labels);
}

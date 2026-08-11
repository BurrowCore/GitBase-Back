package org.os.gitbase.git.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.os.gitbase.git.dto.GitTokenInfo;
import org.os.gitbase.git.entity.GitToken;

import java.util.List;

@Mapper(componentModel = "spring")
public interface GitTokenMapper {
    @Mapping(target = "id", source = "id")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "scopes", source = "scopes")
    @Mapping(target = "createdAt", expression = "java(gitToken.getCreatedAt().toString())")
    @Mapping(target = "expiresAt", expression = "java(gitToken.getExpiresAt().toString())")
    GitTokenInfo toDto(GitToken gitToken);

    List<GitTokenInfo> toDtoList(List<GitToken> gitTokens);
}

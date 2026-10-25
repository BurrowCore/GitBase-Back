package org.os.gitbase.git.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.os.gitbase.git.dto.CommentDto;
import org.os.gitbase.git.dto.IssueDto;
import org.os.gitbase.git.dto.IssueRefDto;
import org.os.gitbase.git.dto.IssueSummaryDto;
import org.os.gitbase.git.entity.Issue;
import org.os.gitbase.git.entity.IssueComment;

/**
 * Entity → DTO mapping for the issue tracker.
 *
 * <p>{@code commentsCount} is not a column — it is a repository count — so it comes in as a second
 * source parameter rather than being derived from the entity. Timestamps convert via
 * {@link TemporalMapper}, and the {@code state} enum maps to its name automatically.
 */
@Mapper(
        componentModel = "spring",
        uses = {TemporalMapper.class, UserSummaryMapper.class, LabelMapper.class}
)
public interface IssueMapper {

    @Mapping(target = "number", source = "issue.number")
    @Mapping(target = "title", source = "issue.title")
    @Mapping(target = "body", source = "issue.body")
    @Mapping(target = "state", source = "issue.state")
    @Mapping(target = "author", source = "issue.author")
    @Mapping(target = "labels", source = "issue.labels")
    @Mapping(target = "assignees", source = "issue.assignees")
    @Mapping(target = "closedBy", source = "issue.closedBy")
    @Mapping(target = "closedByPullNumber", source = "issue.closedByPullNumber")
    @Mapping(target = "createdAt", source = "issue.createdAt")
    @Mapping(target = "updatedAt", source = "issue.updatedAt")
    @Mapping(target = "closedAt", source = "issue.closedAt")
    @Mapping(target = "commentsCount", source = "commentsCount")
    IssueDto toDto(Issue issue, long commentsCount);

    @Mapping(target = "number", source = "issue.number")
    @Mapping(target = "title", source = "issue.title")
    @Mapping(target = "state", source = "issue.state")
    @Mapping(target = "author", source = "issue.author")
    @Mapping(target = "labels", source = "issue.labels")
    @Mapping(target = "assignees", source = "issue.assignees")
    @Mapping(target = "createdAt", source = "issue.createdAt")
    @Mapping(target = "updatedAt", source = "issue.updatedAt")
    @Mapping(target = "commentsCount", source = "commentsCount")
    IssueSummaryDto toSummary(Issue issue, long commentsCount);

    /** Compact reference for dependency lists; {@code state} maps to its enum name. */
    IssueRefDto toRef(Issue issue);

    /** Reuses the pull-request {@code CommentDto} — the conversation shape is identical. */
    CommentDto toCommentDto(IssueComment comment);
}

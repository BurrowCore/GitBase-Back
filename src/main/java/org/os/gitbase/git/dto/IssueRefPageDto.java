package org.os.gitbase.git.dto;

import java.util.List;

/** A page of issue references. */
public record IssueRefPageDto(List<IssueRefDto> issues, int page, int size, long totalElements, boolean hasNext) {}

package org.os.gitbase.git.dto;

/** A user's grant on a repository. */
public record CollaboratorDto(UserSummaryDto user, String permission, Long createdAt) {}

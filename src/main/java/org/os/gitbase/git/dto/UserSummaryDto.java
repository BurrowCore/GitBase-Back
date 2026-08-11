package org.os.gitbase.git.dto;

/** Minimal public identity of a user, embedded in pull-request payloads. */
public record UserSummaryDto(String username, String fullName, String avatarUrl) {
}

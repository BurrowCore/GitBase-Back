package org.os.gitbase.git.dto;

/** A compact reference to an issue, for dependency lists. */
public record IssueRefDto(int number, String title, String state) {}

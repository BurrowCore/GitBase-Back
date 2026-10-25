package org.os.gitbase.git.dto;

/**
 * Where a transferred repository now lives.
 *
 * @param cloneUrlPath new git path ({@code /gitbase/{owner}/{repo}.git}) — old clone URLs no longer work
 */
public record TransferRepositoryResultDto(String owner, String repoName, String cloneUrlPath) {}

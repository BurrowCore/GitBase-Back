package org.os.gitbase.git.service;

import org.os.gitbase.auth.entity.User;
import org.os.gitbase.git.dto.BranchSummaryDto;
import org.os.gitbase.git.dto.CommitDetailDto;
import org.os.gitbase.git.dto.CommitPageDto;
import org.os.gitbase.git.dto.CompareDto;
import org.os.gitbase.git.dto.DirectoryListingDto;
import org.os.gitbase.git.dto.FileContentDto;
import org.os.gitbase.git.dto.FileTreeNode;
import org.os.gitbase.git.dto.RawFileDto;
import org.os.gitbase.git.dto.RepositoryInfo;
import org.os.gitbase.git.dto.RepositoryTreeDto;

import java.util.List;

public interface GitService {
    /** Creates the bare repository with HEAD on {@code defaultBranch} (blank → "main"). */
    void createRepository(String user, String repoName, boolean isPrivate, String defaultBranch);
    RepositoryInfo getRepositoryInfo(String username, String repoName);
    List<RepositoryTreeDto> listRepositories(String user);
    void deleteRepository(String user, String repoName);

    /** Full recursive file tree for a single repository at the given ref (null/blank = default branch). */
    FileTreeNode getTree(String username, String repoName, String ref);

    /** Raw content of a single file (blob) at the given ref. */
    FileContentDto getFileContent(String username, String repoName, String ref, String path);

    /** Raw bytes of a blob, for image preview and file download. */
    RawFileDto getRawFile(String username, String repoName, String ref, String path);

    /**
     * Creates a branch at {@code fromRef} (defaults to HEAD). Operates directly on git refs —
     * there is no branch table (DECISION-007).
     */
    BranchSummaryDto createBranch(String username, String repoName, String branchName, String fromRef);

    /** Deletes a branch ref. The repository's default branch cannot be deleted. */
    void deleteBranch(String username, String repoName, String branchName);

    /**
     * GitHub-style listing of one directory level at the given path/ref, where each entry
     * carries the commit that last modified it, plus the repo's latest commit on the ref.
     */
    DirectoryListingDto listContents(String username, String repoName, String ref, String path);

    /** Paginated commit history for a ref, optionally filtered to a single path. */
    CommitPageDto listCommitHistory(String username, String repoName, String ref, String path, int page, int size);

    /** Full metadata and per-file diff (vs first parent) for a single commit. */
    CommitDetailDto getCommitDetail(String username, String repoName, String sha);

    /** Live list of local branches (refs/heads) with their head commit; default branch flagged. */
    List<BranchSummaryDto> listBranches(String username, String repoName);

    /** Three-way comparison of two refs (ahead/behind, added commits, merge-base diff). PR basis. */
    CompareDto compare(String username, String repoName, String base, String head);
}

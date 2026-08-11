package org.os.gitbase.git.service;

import java.util.UUID;

public interface GitStorageService {
    /**
     * Ensure repository on disk exists and is initialized.
     */
    void ensureRepository(UUID repositoryId, String repoName);

    /**
     * Update branch reference (HEAD) on underlying git storage.
     * If commitHash is null, move to an empty state or throw.
     */
    void updateBranchRef(UUID repositoryId, String branchName, String commitHash) throws Exception;

    /**
     * Optionally create a commit object in underlying git storage and return commit hash.
     * The implementation decides how to compute hash.
     */
    String createCommitOnStorage(UUID repositoryId, String message, String authorEmail, String treeHash, String[] parentHashes) throws Exception;
}

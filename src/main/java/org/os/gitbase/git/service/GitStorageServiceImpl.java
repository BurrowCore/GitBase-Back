package org.os.gitbase.git.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
public class GitStorageServiceImpl implements GitStorageService {

    /**
     * Base folder where all repositories are stored.
     * Example: "gitbase/repositories"
     */
    @Value("${git.storage.base-dir:gitbase/repositories}")
    private String baseDir;

    private File getRepoDir(UUID repositoryId, String repoName) {
        // you can decide folder structure, here I use {baseDir}/{repoId}/{repoName}.git
        return Path.of(baseDir, repositoryId.toString(), repoName + ".git").toFile();
    }

    @Override
    public void ensureRepository(UUID repositoryId, String repoName) {
        File repoDir = getRepoDir(repositoryId, repoName);
        if (!repoDir.exists()) {
            try {
                log.info("Initializing new repository at {}", repoDir.getAbsolutePath());
                Git.init().setBare(true).setDirectory(repoDir).call();
            } catch (GitAPIException e) {
                throw new RuntimeException("Failed to initialize repository: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void updateBranchRef(UUID repositoryId, String branchName, String commitHash) throws Exception {
        File repoDir = getRepoDir(repositoryId, "repo");
        try (Repository repository = new FileRepositoryBuilder()
                .setGitDir(repoDir)
                .readEnvironment()
                .findGitDir()
                .build()) {

            RefUpdate refUpdate = repository.updateRef("refs/heads/" + branchName);
            if (commitHash != null) {
                ObjectId commitId = ObjectId.fromString(commitHash);
                refUpdate.setNewObjectId(commitId);
            } else {
                // detach branch
                refUpdate.setNewObjectId(ObjectId.zeroId());
            }
            refUpdate.update();
            log.info("Updated branch {} to {}", branchName, commitHash);
        }
    }

    @Override
    public String createCommitOnStorage(UUID repositoryId,
                                        String message,
                                        String authorEmail,
                                        String treeHash,
                                        String[] parentHashes) throws Exception {
        File repoDir = getRepoDir(repositoryId, "repo"); // ⚠ adapt to your naming
        try (Repository repository = new FileRepositoryBuilder()
                .setGitDir(repoDir)
                .readEnvironment()
                .findGitDir()
                .build()) {

            // we’ll simulate a commit with empty tree or provided tree hash
            ObjectId treeId = (treeHash != null) ? ObjectId.fromString(treeHash) : repository.resolve("4b825dc642cb6eb9a060e54bf8d69288fbee4904"); // empty tree

            ObjectId[] parents = (parentHashes != null && parentHashes.length > 0)
                    ? Arrays.stream(parentHashes).map(ObjectId::fromString).toArray(ObjectId[]::new)
                    : new ObjectId[0];

            PersonIdent author = new PersonIdent(
                    authorEmail,
                    authorEmail,
                    new Date(),
                    java.util.TimeZone.getDefault()
            );

            RevCommit commit;
            try (var inserter = repository.newObjectInserter()) {
                var commitBuilder = new org.eclipse.jgit.lib.CommitBuilder();
                commitBuilder.setTreeId(treeId);
                commitBuilder.setParentIds(parents);
                commitBuilder.setAuthor(author);
                commitBuilder.setCommitter(author);
                commitBuilder.setMessage(message);

                ObjectId commitId = inserter.insert(commitBuilder);
                inserter.flush();

                commit = repository.parseCommit(commitId);
            }

            log.info("Created commit {} in repo {}", commit.getId().getName(), repositoryId);
            return commit.getId().getName();
        }
    }
}


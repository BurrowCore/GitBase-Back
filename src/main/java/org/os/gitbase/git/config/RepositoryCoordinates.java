package org.os.gitbase.git.config;

import java.io.File;
import java.util.Optional;

/**
 * The {@code owner/repoName} identity of a bare repository, derived from its on-disk location
 * ({@code .../repositories/{owner}/{repoName}.git}).
 *
 * <p>JGit hands receive-pack factories and hooks an opened {@link org.eclipse.jgit.lib.Repository},
 * not the request path, so this is how they recover which repository they are acting on.
 */
public record RepositoryCoordinates(String owner, String repoName) {

    private static final String GIT_SUFFIX = ".git";

    /** Coordinates for a bare repository directory, or empty if it does not follow the layout. */
    public static Optional<RepositoryCoordinates> fromGitDir(File gitDir) {
        if (gitDir == null) {
            return Optional.empty();
        }
        String dirName = gitDir.getName();
        String repoName = dirName.endsWith(GIT_SUFFIX)
                ? dirName.substring(0, dirName.length() - GIT_SUFFIX.length())
                : dirName;
        File parent = gitDir.getParentFile();
        String owner = parent != null ? parent.getName() : null;
        if (owner == null || owner.isBlank() || repoName.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new RepositoryCoordinates(owner, repoName));
    }
}

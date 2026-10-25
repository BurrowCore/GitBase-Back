package org.os.gitbase.git.config;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryCoordinatesTest {

    @Test
    void parsesOwnerAndNameFromBareRepositoryDirectory() {
        File gitDir = new File("./gitbase/repositories/alice/project.git");

        assertThat(RepositoryCoordinates.fromGitDir(gitDir))
                .contains(new RepositoryCoordinates("alice", "project"));
    }

    @Test
    void acceptsDirectoryWithoutGitSuffix() {
        assertThat(RepositoryCoordinates.fromGitDir(new File("/data/alice/project")))
                .contains(new RepositoryCoordinates("alice", "project"));
    }

    @Test
    void rejectsMissingOrOwnerlessDirectories() {
        assertThat(RepositoryCoordinates.fromGitDir(null)).isEmpty();
        assertThat(RepositoryCoordinates.fromGitDir(new File("project.git"))).isEmpty();
        assertThat(RepositoryCoordinates.fromGitDir(new File("/alice/.git"))).isEmpty();
    }
}

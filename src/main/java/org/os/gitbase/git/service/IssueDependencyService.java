package org.os.gitbase.git.service;

import org.os.gitbase.git.dto.IssueRefDto;
import org.os.gitbase.git.dto.IssueRefPageDto;

/**
 * Blocking relationships between issues of one repository: "#5 depends on #3" means #3 blocks #5,
 * and #5 cannot be closed by hand while #3 is open.
 */
public interface IssueDependencyService {

    /** Issues {@code number} depends on ("blocked by"). */
    IssueRefPageDto dependencies(String owner, String repoName, int number, int page, int size);

    /** Issues that depend on {@code number} ("blocks"). */
    IssueRefPageDto dependents(String owner, String repoName, int number, int page, int size);

    /**
     * Records that {@code number} depends on {@code dependencyNumber}. Write access required
     * (being the issue's author is not enough).
     *
     * @throws IllegalArgumentException for a self-dependency, or a number that is not an issue (400)
     * @throws org.os.gitbase.exception.ConflictException if the link exists or would form a cycle (409)
     */
    IssueRefDto addDependency(String owner, String repoName, int number, int dependencyNumber,
                              String actor, boolean actorHasWriteAccess);

    void removeDependency(String owner, String repoName, int number, int dependencyNumber,
                          String actor, boolean actorHasWriteAccess);
}

package org.os.gitbase.git.service;

import org.os.gitbase.git.dto.CollaboratorDto;

import java.util.List;

/**
 * Manages who may access a repository besides its owner.
 *
 * <p>Follows Gitea: a grant takes effect immediately, with no invitation/acceptance step.
 * Only the repository owner may call these — enforced by the controller via
 * {@code RepositoryAccessGuard.requireOwner}.
 */
public interface CollaboratorService {

    List<CollaboratorDto> list(String owner, String repoName);

    /** Adds the collaborator, or updates their permission if the grant already exists. */
    CollaboratorDto set(String owner, String repoName, String username, String permission);

    void remove(String owner, String repoName, String username);
}

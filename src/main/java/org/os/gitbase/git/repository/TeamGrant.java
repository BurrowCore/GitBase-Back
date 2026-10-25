package org.os.gitbase.git.repository;

import org.os.gitbase.git.entity.enums.RepositoryPermission;

/** One team's grant on a repository, as returned by {@link TeamRepository#findGrants}. */
public record TeamGrant(RepositoryPermission permission, boolean ownerTeam) {
}

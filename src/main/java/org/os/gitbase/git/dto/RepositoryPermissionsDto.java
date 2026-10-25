package org.os.gitbase.git.dto;

import org.os.gitbase.git.entity.enums.RepositoryPermission;

import java.util.Optional;

/**
 * What the <b>viewer</b> may do in a repository, attached to the repository payload so the UI
 * renders only the controls that will work.
 * Advisory only: every endpoint still enforces its own check.
 *
 * @param pull  may read / clone
 * @param push  may push, merge pull requests, manage branches
 * @param admin may change repository settings
 * @param owner owns the repository — the only role that may delete it or manage collaborators
 */
public record RepositoryPermissionsDto(boolean pull, boolean push, boolean admin, boolean owner) {

    public static RepositoryPermissionsDto of(Optional<RepositoryPermission> permission, boolean owner) {
        boolean pull = permission.isPresent();
        boolean push = permission.filter(p -> p.atLeast(RepositoryPermission.WRITE)).isPresent();
        boolean admin = permission.filter(p -> p.atLeast(RepositoryPermission.ADMIN)).isPresent();
        return new RepositoryPermissionsDto(pull, push, admin, owner);
    }
}

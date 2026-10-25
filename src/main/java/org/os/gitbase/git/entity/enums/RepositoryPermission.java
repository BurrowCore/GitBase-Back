package org.os.gitbase.git.entity.enums;

/**
 * Access ladder for a repository collaborator or team.
 *
 * <p>The repository <b>owner</b> is deliberately not a value here: ownership comes from the
 * {@code repositories.user_id} column, always outranks every collaborator level, and is the only
 * role permitted to delete the repository or manage its collaborators.
 *
 * <p>Three levels are deliberate: they cover the real boundaries (see a private repo / push and
 * merge / change settings) without the extra triage/maintain rungs larger platforms use.
 */
public enum RepositoryPermission {

    /** View a private repository, clone/fetch, open issues and comment. */
    READ(1),

    /** Everything READ can do, plus push, create/delete branches, and merge pull requests. */
    WRITE(2),

    /** Everything WRITE can do, plus repository settings. Cannot delete the repo or manage collaborators. */
    ADMIN(3);

    private final int level;

    RepositoryPermission(int level) {
        this.level = level;
    }

    /** Numeric rank, used for "at least this permission" comparisons. */
    public int level() {
        return level;
    }

    /** True when this permission is at least as strong as {@code required}. */
    public boolean atLeast(RepositoryPermission required) {
        return this.level >= required.level;
    }
}

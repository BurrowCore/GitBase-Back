package org.os.gitbase.auth.entity.enums;

/**
 * What a {@code t_users} row is. Organizations live in the same table as users
 * so they share one namespace and {@code /{owner}/{repo}} resolves the same way for both.
 * Organizations cannot log in: they have no password, are disabled, and carry a non-routable email.
 */
public enum AccountType {
    USER,
    ORGANIZATION
}

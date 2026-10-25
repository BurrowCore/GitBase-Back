package org.os.gitbase.git.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.security.Principal;

/**
 * Resolves the authenticated caller of a smart-HTTP git request ({@code /gitbase/*}).
 *
 * <p>Shared by the repository resolver (read gate) and the receive-pack factory (write gate) so
 * both doors identify the caller the same way.
 */
final class GitRequestPrincipal {

    private GitRequestPrincipal() {
    }

    /** The caller from the request, falling back to the security context; null when anonymous. */
    static Principal of(HttpServletRequest req) {
        Principal principal = req.getUserPrincipal();
        if (principal != null) {
            return principal;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.isAuthenticated()) ? auth : null;
    }
}

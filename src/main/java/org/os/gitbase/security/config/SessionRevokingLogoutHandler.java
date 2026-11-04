package org.os.gitbase.security.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.os.gitbase.auth.service.AuthCookies;
import org.os.gitbase.auth.service.AuthSessionService;
import org.os.gitbase.constant.Constant;
import org.os.gitbase.jwt.JwtTokenProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

/**
 * Sign-out that actually invalidates the tokens. Deleting cookies alone leaves any copy of them
 * usable until it expires; this revokes the sign-in session server-side, so the access token and
 * every refresh token of that session are refused from now on.
 *
 * <p>The session is found from the refresh cookie, or failing that from the access token's
 * {@code sid} (accepted even if the access token has expired). Never throws — sign-out must always
 * succeed for the user.
 */
@Slf4j
@Component
public class SessionRevokingLogoutHandler implements LogoutHandler {

    private final AuthSessionService sessionService;
    private final JwtTokenProvider tokenProvider;
    private final AuthCookies cookies;

    public SessionRevokingLogoutHandler(AuthSessionService sessionService, JwtTokenProvider tokenProvider,
                                        AuthCookies cookies) {
        this.sessionService = sessionService;
        this.tokenProvider = tokenProvider;
        this.cookies = cookies;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        // Each path on its own, so a failure of one still lets the other revoke the session.
        try {
            cookies.read(request, Constant.REFRESH_TOKEN).ifPresent(sessionService::signOutByRefreshToken);
        } catch (RuntimeException e) {
            log.error("Sign-out could not revoke the session by refresh token", e);
        }
        try {
            cookies.read(request, Constant.ACCESS_TOKEN)
                    .flatMap(this::sessionIdOf)
                    .ifPresent(sessionService::signOutBySessionId);
        } catch (RuntimeException e) {
            log.error("Sign-out could not revoke the session by access token", e);
        }
        cookies.clear(response);
    }

    private java.util.Optional<java.util.UUID> sessionIdOf(String accessToken) {
        try {
            return tokenProvider.extractSessionId(accessToken);
        } catch (RuntimeException invalid) {
            return java.util.Optional.empty(); // forged or malformed: nothing to revoke
        }
    }
}

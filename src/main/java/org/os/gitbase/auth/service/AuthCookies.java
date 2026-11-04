package org.os.gitbase.auth.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.os.gitbase.constant.Constant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * The two authentication cookies, set and cleared in one place so every sign-in path (password,
 * Google ID token, OAuth redirect) issues them identically.
 *
 * <ul>
 *   <li>Access token: HttpOnly, sent everywhere, lives as long as the token (15 minutes).</li>
 *   <li>Refresh token: HttpOnly, sent <b>only</b> to {@code /api/v1/auth} (refresh and sign-out),
 *       so it never travels with ordinary API calls.</li>
 * </ul>
 * Both are {@code SameSite=Lax}; {@code Secure} follows {@code gitbase.cookies.secure} (true behind HTTPS).
 */
@Component
public class AuthCookies {

    static final String REFRESH_COOKIE_PATH = Constant.AUTH_MAPPING_REQUEST;

    private final boolean secure;

    public AuthCookies(@Value("${gitbase.cookies.secure:false}") boolean secure) {
        this.secure = secure;
    }

    /** Adds the cookies for freshly issued tokens (the refresh cookie only when one was issued). */
    public void write(HttpServletResponse response, AuthSessionService.IssuedTokens tokens) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookie(Constant.ACCESS_TOKEN, tokens.accessToken(), "/", AuthSessionService.ACCESS_TOKEN_TTL).toString());
        if (tokens.refreshToken() == null) {
            return;
        }
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookie(Constant.REFRESH_TOKEN, tokens.refreshToken(), REFRESH_COOKIE_PATH,
                        AuthSessionService.REFRESH_TOKEN_TTL).toString());
    }

    /** Expires both cookies (and refresh cookies left on {@code /} by older versions). */
    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(Constant.ACCESS_TOKEN, "", "/", Duration.ZERO).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookie(Constant.REFRESH_TOKEN, "", REFRESH_COOKIE_PATH, Duration.ZERO).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(Constant.REFRESH_TOKEN, "", "/", Duration.ZERO).toString());
    }

    public Optional<String> read(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    private ResponseCookie cookie(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(path)
                .maxAge(maxAge)
                .build();
    }
}

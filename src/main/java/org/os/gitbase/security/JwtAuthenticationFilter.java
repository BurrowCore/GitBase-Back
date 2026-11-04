package org.os.gitbase.security;

import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.os.gitbase.auth.service.AuthSessionService;
import org.os.gitbase.auth.service.UserDetailService;
import org.os.gitbase.constant.Constant;
import org.os.gitbase.jwt.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Configuration
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private UserDetailService userDetailService;

    @Autowired
    private AuthSessionService sessionService;

    private static final String REFRESH_PATH = Constant.AUTH_MAPPING_REQUEST + Constant.REFRESH_TOKEN_MAPPING_REQUEST;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String requestURI = request.getRequestURI();
        
        // Skip JWT processing for OAuth2 and refresh token endpoints
        // (The refresh endpoint must work with an expired access cookie still attached.)
        if (REFRESH_PATH.equals(requestURI) ||
            requestURI.startsWith("/oauth2/") ||
            requestURI.startsWith("/login/oauth2/code/")) {
            filterChain.doFilter(request, response);
            return;
        }
        
        String token = null;
        String username = null;

        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (cookie.getName().equals(Constant.ACCESS_TOKEN)) {
                    token = cookie.getValue();
                    break;
                }
            }
        }
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            if (this.tokenProvider.isTokenExpired(token)) {
                logger.error("Jwt expired");
            }
            username = this.tokenProvider.extractUsername(token);
            if (username != null) {
                try {
                    UserDetails userDetails = userDetailService.loadUserByUsername(username);
                    // A valid signature is not enough: the token's sign-in session must still be
                    // open, so a signed-out (or stolen-and-revoked) token is refused immediately.
                    if (this.tokenProvider.validateToken(token, userDetails) && sessionIsActive(token)) {
                        UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());
                        authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authenticationToken);
                    }
                } catch (jakarta.persistence.EntityNotFoundException e) {
                    logger.warn("User not found in database for token: {}", username);
                    // Continue with the filter chain without setting authentication
                }
            }
        } catch (ExpiredJwtException e) {
            logger.error("Session expired");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "JWT token expired");
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\": \"JWT token expired\", \"message\": \"" + e.getMessage() + "\"}");
            return;
        } catch (io.jsonwebtoken.JwtException | IllegalArgumentException e) {
            // Forged or malformed cookie: stay anonymous, so protected endpoints answer 401, not 500.
            logger.warn("Rejected access token: {}", e.getClass().getSimpleName());
        }
        filterChain.doFilter(request, response);
    }

    /** Tokens without a session id predate sessions and are refused — their holders sign in again. */
    private boolean sessionIsActive(String token) {
        return this.tokenProvider.extractSessionId(token).map(sessionService::isActive).orElse(false);
    }
}

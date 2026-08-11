package org.os.gitbase.security.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class OAuth2AuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private static final Logger logger = LoggerFactory.getLogger(OAuth2AuthenticationFailureHandler.class);

    @Value("${app.oauth2.failure-redirect-uri}")
    private String authorizedRedirectUri;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {

        String targetUrl = determineFailureUrl(request, exception);

        logger.error("OAuth2 authentication failed: {}", exception.getMessage(), exception);

        if (response.isCommitted()) {
            logger.debug("Response has already been committed. Unable to redirect to " + targetUrl);
            return;
        }

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    private String determineFailureUrl(HttpServletRequest request, AuthenticationException exception) {

        // Determine error type and message
        String errorType = "authentication_failed";
        String errorMessage = "Authentication failed";

        if (exception instanceof OAuth2AuthenticationException) {
            OAuth2AuthenticationException oauth2Exception = (OAuth2AuthenticationException) exception;
            errorType = oauth2Exception.getError().getErrorCode();
            errorMessage = oauth2Exception.getError().getDescription();
        } else {
            // Handle other authentication exceptions
            if (exception.getMessage() != null) {
                if (exception.getMessage().contains("email")) {
                    errorType = "email_not_found";
                    errorMessage = "Email not found from OAuth2 provider";
                } else if (exception.getMessage().contains("access_denied")) {
                    errorType = "access_denied";
                    errorMessage = "User denied access";
                } else if (exception.getMessage().contains("invalid_request")) {
                    errorType = "invalid_request";
                    errorMessage = "Invalid OAuth2 request";
                } else {
                    errorMessage = exception.getMessage();
                }
            }
        }

        // Ensure errorMessage is not null before encoding
        if (errorMessage == null) {
            errorMessage = "Unknown authentication error";
        }

        String encodedErrorMessage = URLEncoder.encode(errorMessage, StandardCharsets.UTF_8);

        return UriComponentsBuilder.fromUriString("http://localhost:5173/login")
                .queryParam("error", errorType)
                .queryParam("message", encodedErrorMessage)
                .build().toUriString();
    }
}

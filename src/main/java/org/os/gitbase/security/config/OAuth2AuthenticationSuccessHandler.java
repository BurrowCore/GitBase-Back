package org.os.gitbase.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.os.gitbase.auth.dto.AuthResponse;
import org.os.gitbase.auth.dto.UserInfo;
import org.os.gitbase.auth.entity.Role;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.entity.enums.AuthProvider;
import org.os.gitbase.auth.mapper.UserMapper;
import org.os.gitbase.auth.repository.RoleRepository;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.common.ApiResponseEntity;
import org.os.gitbase.google.UserPrincipal;
import org.os.gitbase.auth.service.AuthCookies;
import org.os.gitbase.auth.service.AuthSessionService;
import org.os.gitbase.auth.service.UsernameAllocator;
import org.os.gitbase.jwt.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.os.gitbase.constant.Constant;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Slf4j
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    @Autowired
    private JwtTokenProvider tokenProvider;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private UserMapper userMapper;
    @Qualifier("httpCookieOAuth2AuthorizationRequestRepository")
    @Autowired
    private HttpCookieOAuth2AuthorizationRequestRepository authorizationRequestRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Value("${app.oauth2.frontend-redirect-uri}")
    private String frontendRedirectUri;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final UsernameAllocator usernameAllocator;
    private final AuthSessionService sessionService;
    private final AuthCookies authCookies;

    public OAuth2AuthenticationSuccessHandler(UsernameAllocator usernameAllocator, AuthSessionService sessionService,
                                              AuthCookies authCookies) {
        this.usernameAllocator = usernameAllocator;
        this.sessionService = sessionService;
        this.authCookies = authCookies;
    }



    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {

        log.info("OAuth2AuthenticationSuccessHandler called");
        log.info("Authentication principal type: {}", authentication.getPrincipal().getClass().getName());
        log.info("Authentication principal: {}", authentication.getPrincipal());

        try {
            // Prepare variables
            String email;
            String name;
            String profilePictureUrl;
            List<String> roles;

            if (authentication.getPrincipal() instanceof DefaultOidcUser oidcUser) {
                email = oidcUser.getEmail();
                name = oidcUser.getName();
                profilePictureUrl = oidcUser.getPicture();
                roles = oidcUser.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toList());
            } else if (authentication.getPrincipal() instanceof UserPrincipal userPrincipal) {
                email = userPrincipal.getEmail();
                name = userPrincipal.getName();
                if (userPrincipal.getAttributes() != null) {
                    profilePictureUrl = (String) userPrincipal.getAttributes().get("picture");
                } else {
                    profilePictureUrl = null;
                }
                roles = userPrincipal.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toList());
            } else {
                email = authentication.getName();
                name = authentication.getName();
                profilePictureUrl = null;
                roles = authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toList());
            }

            if (email == null) {
                throw new RuntimeException("Email not found in OAuth2 user");
            }

            // Get the actual user from database, or create if not exists
            User user = userRepository.findUserByEmail(email).orElseGet(() -> {
                User newUser = new User();
                newUser.setEmail(email);
                newUser.setName(usernameAllocator.allocate(email));
                newUser.setProfilePictureUrl(profilePictureUrl);
                newUser.setAuthProvider(AuthProvider.GOOGLE);
                newUser.setEmailVerified(true);
                newUser.setEnabled(true);
                newUser.setLastLogin(LocalDateTime.now());
                // Assign default role
                Role userRole = roleRepository.findRoleByRoleName("ROLE_USER")
                    .orElseThrow(() -> new RuntimeException("Default USER role not found."));
                HashSet<Role> rolesSet = new HashSet<>();
                rolesSet.add(userRole);
                newUser.setRoles(rolesSet);
                return userRepository.save(newUser);
            });

            log.info("Found or created user in database: {} with ID: {}", user.getEmail(), user.getId());

            // Same session, tokens and cookies as a password sign-in.
            authCookies.write(response, sessionService.signIn(user));

            // Set response headers
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");


            log.info("OAuth2 login successful for user: {} with ID: {}", email, user.getId());
            authorizationRequestRepository.removeAuthorizationRequestCookies(request, response);

            // Clear authentication attributes
            clearAuthenticationAttributes(request);
            response.sendRedirect(frontendRedirectUri);
        } catch (Exception e) {
            log.error("Error in OAuth2 success handler", e);

            // Return error response as JSON
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");

            ApiResponseEntity<String> errorResponse = new ApiResponseEntity<>(
                    Instant.now(),
                    false,
                    "OAuth2 authentication failed: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    null
            );

            String errorJson = objectMapper.writeValueAsString(errorResponse);
            response.getWriter().write(errorJson);
        }
    }

    private void clearAuthenticationAttributes(HttpServletRequest request) {
        request.getSession().removeAttribute("SPRING_SECURITY_SAVED_REQUEST");
    }


}
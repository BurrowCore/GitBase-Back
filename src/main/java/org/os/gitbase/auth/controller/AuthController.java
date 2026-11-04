package org.os.gitbase.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import lombok.extern.slf4j.Slf4j;
import org.os.gitbase.auth.dto.*;
import org.os.gitbase.auth.entity.Role;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.service.UserService;
import org.os.gitbase.auth.service.UsernameAllocator;
import org.os.gitbase.auth.service.AuthCookies;
import org.os.gitbase.auth.service.AuthSessionService;
import org.springframework.security.authentication.BadCredentialsException;
import org.os.gitbase.common.ApiResponseEntity;
import org.os.gitbase.constant.Constant;

import org.os.gitbase.google.GoogleTokenVerifier;
import org.os.gitbase.google.GoogleUserInfo;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;

@RestController
@RequestMapping(Constant.AUTH_MAPPING_REQUEST)
@Slf4j
public class AuthController {

    private final UserService userService;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final PasswordEncoder passwordEncoder;
    private final UsernameAllocator usernameAllocator;
    private final AuthSessionService sessionService;
    private final AuthCookies authCookies;

    private static final long ACCESS_TOKEN_SECONDS = AuthSessionService.ACCESS_TOKEN_TTL.toSeconds();

    @Autowired(required = false)
    private OAuth2AuthorizedClientService authorizedClientService;

    public AuthController(UserService userService, GoogleTokenVerifier googleTokenVerifier, PasswordEncoder passwordEncoder,
                          UsernameAllocator usernameAllocator, AuthSessionService sessionService,
                          AuthCookies authCookies) {
        this.userService = userService;
        this.googleTokenVerifier = googleTokenVerifier;
        this.passwordEncoder = passwordEncoder;
        this.usernameAllocator = usernameAllocator;
        this.sessionService = sessionService;
        this.authCookies = authCookies;
    }

    /**
     * Test endpoint to verify OAuth2 configuration
     */
    @GetMapping("/oauth2/test")
    public ResponseEntity<ApiResponseEntity<String>> testOAuth2() {
        return ResponseEntity.ok(new ApiResponseEntity<>(
                Instant.now(),
                true,
                "OAuth2 configuration is working",
                HttpStatus.OK,
                "OAuth2 endpoints are properly configured"
        ));
    }

    /**
     * Register a new user
     */
    @PostMapping(Constant.REGISTER_MAPPING_REQUEST)
    public ResponseEntity<ApiResponseEntity<String>> register(
            @Valid @RequestBody RegisterDTO registerDTO) {
        try {
            // Check if user already exists
            boolean exists = userService.existsByEmail(registerDTO.getEmail());
            if (exists) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new ApiResponseEntity<>(
                                Instant.now(),
                                false,
                                "User with this email already exists",
                                HttpStatus.CONFLICT,
                                null
                        ));
            }

            // Create new user
            User newUser = new User();
            // Unique username (alice, alice-2, …) — never the bare email local part, which collides.
            newUser.setName(usernameAllocator.allocate(registerDTO.getEmail()));
            newUser.setFullName(registerDTO.getFullName());
            newUser.setEmail(registerDTO.getEmail());
            newUser.setPassword(passwordEncoder.encode(registerDTO.getPassword()));
            newUser.setAuthProvider(org.os.gitbase.auth.entity.enums.AuthProvider.LOCAL);
            newUser.setEnabled(true);
            newUser.setEmailVerified(false);
            userService.add(newUser);

            return ResponseEntity.ok(new ApiResponseEntity<>(
                    Instant.now(),
                    true,
                    "User registered successfully",
                    HttpStatus.OK,
                    null
            ));

        } catch (Exception e) {
            log.error("Error during user registration", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponseEntity<>(
                            Instant.now(),
                            false,
                            "Internal server error during registration",
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            null
                    ));
        }
    }

    /**
     * Login with email and password
     */
    @PostMapping(Constant.LOGIN_MAPPING_REQUEST)
    public ResponseEntity<ApiResponseEntity<AuthResponse>> login(
            @Valid @RequestBody LoginDTO loginDTO,
            HttpServletResponse response) {
        try {
            // Authenticate user
            AuthSessionService.IssuedTokens tokens = userService.authenticate(loginDTO);
            
            // Get user info
            UserInfo userInfo = userService.findByEmail(loginDTO.getEmail());
            
            if (userInfo != null) {
                AuthResponse authResponse = AuthResponse.builder()
                        .expiresIn(ACCESS_TOKEN_SECONDS)
                        .user(userInfo)
                        .build();
                authCookies.write(response, tokens);

                return ResponseEntity.ok(new ApiResponseEntity<>(
                        Instant.now(),
                        true,
                        "Login successful",
                        HttpStatus.OK,
                        authResponse
                ));
            } else {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ApiResponseEntity<>(
                                Instant.now(),
                                false,
                                "Invalid credentials",
                                HttpStatus.UNAUTHORIZED,
                                null
                        ));
            }

        } catch (Exception e) {
            log.error("Error during login", e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ApiResponseEntity<>(
                            Instant.now(),
                            false,
                            "Invalid credentials",
                            HttpStatus.UNAUTHORIZED,
                            null
                    ));
        }
    }

    /**
     * Get OAuth2 authorization URL for Google
     */
    @GetMapping(Constant.OAUTH2_GOOGLE_URL_REQUEST)
    public ResponseEntity<ApiResponseEntity<OAuthUrlResponse>> getGoogleOAuthUrl(HttpServletRequest request) {
        try {
            // Use Spring Security's built-in OAuth2 authorization endpoint
            String baseUrl = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
            String authorizationUrl = baseUrl + "/oauth2/authorize/google";

            // Generate state for security (optional, Spring Security handles this)
            // TODO => change the UUID.randomUUID().toString() with secure random to avoid cracking
            String state = UUID.randomUUID().toString();

            OAuthUrlResponse response = OAuthUrlResponse.builder()
                    .authorizationUrl(authorizationUrl)
                    .state(state)
                    .build();

            return ResponseEntity.ok(new ApiResponseEntity<>(
                    Instant.now(),
                    true,
                    "OAuth2 URL generated successfully",
                    HttpStatus.OK,
                    response
            ));
        } catch (Exception e) {
            log.error("Error generating OAuth2 URL", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponseEntity<>(
                            Instant.now(),
                            false,
                            "Failed to generate OAuth2 URL",
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            null
                    ));
        }
    }
    /**
     * Authenticate with Google ID token
     */
    @PostMapping(Constant.GOOGLE_REQUEST)
    public ResponseEntity<ApiResponseEntity<AuthResponse>> authenticateWithGoogle(
            @Valid @RequestBody GoogleAuthRequest googleAuthRequest,
            HttpServletResponse response) {
        try {
            // Verify the Google ID token
            GoogleUserInfo googleUserInfo = googleTokenVerifier.verifyIdToken(googleAuthRequest.getIdToken());
            
            if (googleUserInfo != null) {
                UserInfo userInfo = userService.findByEmail(googleUserInfo.getEmail());
                
                if (userInfo != null) {
                    authCookies.write(response, userService.signIn(googleUserInfo.getEmail()));

                    AuthResponse authResponse = AuthResponse.builder()
                            .expiresIn(ACCESS_TOKEN_SECONDS)
                            .user(userInfo)
                            .build();
                    
                    return ResponseEntity.ok(new ApiResponseEntity<>(
                            Instant.now(),
                            true,
                            "Google authentication successful",
                            HttpStatus.OK,
                            authResponse
                    ));
                } else {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(new ApiResponseEntity<>(
                                    Instant.now(),
                                    false,
                                    "User not found",
                                    HttpStatus.NOT_FOUND,
                                    null
                            ));
                }
            } else {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new ApiResponseEntity<>(
                                Instant.now(),
                                false,
                                "Invalid Google ID token",
                                HttpStatus.BAD_REQUEST,
                                null
                        ));
            }
            
        } catch (Exception e) {
            log.error("Error during Google authentication", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponseEntity<>(
                            Instant.now(),
                            false,
                            "Internal server error during Google authentication",
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            null
                    ));
        }
    }

    /**
     * Get current authenticated user info
     */
    @GetMapping(Constant.PROFILE_INFO_MAPPING_REQUEST)
    public ResponseEntity<ApiResponseEntity<UserInfo>> getCurrentUser() {
        try {
            UserInfo userInfo = userService.getUser();
            
            if (userInfo != null) {
                return ResponseEntity.ok(new ApiResponseEntity<>(
                        null,
                        true,
                        null,
                        HttpStatus.OK,
                        userInfo
                ));
            } else {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ApiResponseEntity<>(
                                Instant.now(),
                                false,
                                "User not authenticated",
                                HttpStatus.UNAUTHORIZED,
                                null
                        ));
            }
        } catch (Exception e) {
            log.error("Error retrieving current user", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponseEntity<>(
                            Instant.now(),
                            false,
                            "Internal server error",
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            null
                    ));
        }
    }

    /**
     * Exchanges the refresh cookie for new tokens. The refresh token is single-use (rotation); a
     * replayed one ends the whole sign-in session. Any failure clears the cookies and answers 401,
     * so the client signs in again.
     */
    @PostMapping(Constant.REFRESH_TOKEN_MAPPING_REQUEST)
    public ResponseEntity<ApiResponseEntity<AuthResponse>> refreshToken(
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            String refreshToken = authCookies.read(request, Constant.REFRESH_TOKEN)
                    .orElseThrow(() -> new BadCredentialsException("No refresh token"));
            authCookies.write(response, sessionService.refresh(refreshToken));
            AuthResponse authResponse = AuthResponse.builder().expiresIn(ACCESS_TOKEN_SECONDS).build();
            return ResponseEntity.ok(new ApiResponseEntity<>(
                    Instant.now(), true, "Token refreshed successfully", HttpStatus.OK, authResponse));
        } catch (BadCredentialsException e) {
            authCookies.clear(response);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiResponseEntity<>(
                    Instant.now(), false, "Session expired — please sign in again", HttpStatus.UNAUTHORIZED, null));
        }
    }

    // Sign-out is handled by Spring Security's logout filter on POST /api/v1/auth/logout, with
    // SessionRevokingLogoutHandler revoking the session — no controller method is reached there.
}

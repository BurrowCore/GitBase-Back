package org.os.gitbase.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import lombok.extern.slf4j.Slf4j;
import org.os.gitbase.auth.dto.*;
import org.os.gitbase.auth.entity.Role;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.entity.jwt.RefreshToken;
import org.os.gitbase.auth.service.UserService;
import org.os.gitbase.common.ApiResponseEntity;
import org.os.gitbase.constant.Constant;

import org.os.gitbase.google.GoogleTokenVerifier;
import org.os.gitbase.google.GoogleUserInfo;
import org.os.gitbase.helper.Helper;
import org.os.gitbase.jwt.JwtTokenProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;

@RestController
@RequestMapping(Constant.AUTH_MAPPING_REQUEST)
@Slf4j
public class AuthController {

    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final SecurityContextLogoutHandler logoutHandler;
    private final PasswordEncoder passwordEncoder;
    
    @Autowired(required = false)
    private OAuth2AuthorizedClientService authorizedClientService;

    public AuthController(UserService userService, JwtTokenProvider jwtTokenProvider, GoogleTokenVerifier googleTokenVerifier, SecurityContextLogoutHandler logoutHandler, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.googleTokenVerifier = googleTokenVerifier;
        this.logoutHandler = logoutHandler;
        this.passwordEncoder = passwordEncoder;
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
            newUser.setName(Helper.removeAtSymbolAndFollowing(registerDTO.getEmail()));
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
            Map<String, ResponseCookie> cookies = userService.authenticate(loginDTO);
            
            // Get user info
            UserInfo userInfo = userService.findByEmail(loginDTO.getEmail());
            
            if (userInfo != null) {
                // Extract tokens from cookies
                String accessToken = cookies.get(Constant.ACCESS_TOKEN).getValue();
                String refreshToken = cookies.get(Constant.REFRESH_TOKEN).getValue();

                AuthResponse authResponse = AuthResponse.builder()
                        .expiresIn(3600L)
                        .user(userInfo)
                        .build();

                // Set cookies
                response.addHeader("Set-Cookie", cookies.get(Constant.ACCESS_TOKEN).toString());
                response.addHeader("Set-Cookie", cookies.get(Constant.REFRESH_TOKEN).toString());

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
                    String accessToken = jwtTokenProvider.generateToken(googleUserInfo.getEmail(), userInfo.getRoles().stream()
                            .map(Role::getRoleName)
                            .toList());
                    RefreshToken refreshTokenEntity = jwtTokenProvider.createRefreshToken(googleUserInfo.getEmail());
                    
                    AuthResponse authResponse = AuthResponse.builder()
                            .expiresIn(3600L)
                            .user(userInfo)
                            .build();
                    
                    response.addHeader("Set-Cookie", 
                            userService.createAccessTokenCookie(accessToken).toString());
                    response.addHeader("Set-Cookie", 
                            userService.createRefreshTokenCookie(refreshTokenEntity.getToken()).toString());
                    
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
     * Refresh access token using refresh token
     */
    @PostMapping(Constant.REFRESH_TOKEN_MAPPING_REQUEST)
    public ResponseEntity<ApiResponseEntity<AuthResponse>> refreshToken(
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            String refreshToken = extractRefreshTokenFromCookies(request);
            
            if (refreshToken != null && jwtTokenProvider.validateRefreshToken(refreshToken)) {
                // Find the refresh token entity
                var refreshTokenOpt = jwtTokenProvider.findByToken(refreshToken);
                if (refreshTokenOpt.isPresent()) {
                    RefreshToken refreshTokenEntity = jwtTokenProvider.verifyExpiration(refreshTokenOpt.get());
                    String email = refreshTokenEntity.getUser().getEmail();
                    
                    // Generate new access token
                    String newAccessToken = jwtTokenProvider.generateToken(email, refreshTokenEntity.getUser().getRoles().stream()
                            .map(role -> role.getRoleName())
                            .toList());
                    
                    AuthResponse authResponse = AuthResponse.builder()
                            .expiresIn(3600L)
                            .build();
                    
                    response.addHeader("Set-Cookie", 
                            userService.createAccessTokenCookie(newAccessToken).toString());
                    
                    return ResponseEntity.ok(new ApiResponseEntity<>(
                            Instant.now(),
                            true,
                            "Token refreshed successfully",
                            HttpStatus.OK,
                            authResponse
                    ));
                }
            }
            
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ApiResponseEntity<>(
                            Instant.now(),
                            false,
                            "Invalid refresh token",
                            HttpStatus.UNAUTHORIZED,
                            null
                    ));
        } catch (Exception e) {
            log.error("Error refreshing token", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponseEntity<>(
                            Instant.now(),
                            false,
                            "Internal server error during token refresh",
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            null
                    ));
        }
    }

    /**
     * Logout user and clear cookies
     */
    @PostMapping(Constant.LOGOUT_MAPPING_REQUEST)
    public ResponseEntity<ApiResponseEntity<String>> logout(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            SecurityContextHolder.clearContext();
            
            this.logoutHandler.logout(request, response, authentication);
            
            return ResponseEntity.ok(new ApiResponseEntity<>(
                    Instant.now(),
                    true,
                    "Logout successful",
                    HttpStatus.OK,
                    "User logged out successfully"
            ));
        } catch (Exception e) {
            log.error("Error during logout", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponseEntity<>(
                            Instant.now(),
                            false,
                            "Internal server error during logout",
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            null
                    ));
        }
    }

    // Helper methods
    private String extractRefreshTokenFromCookies(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                if (Constant.REFRESH_TOKEN.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}

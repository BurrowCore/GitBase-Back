package org.os.gitbase.auth.service.impl;

import jakarta.persistence.EntityNotFoundException;
import org.os.gitbase.auth.dto.LoginDTO;
import org.os.gitbase.auth.dto.UserInfo;
import org.os.gitbase.auth.entity.Role;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.entity.jwt.RefreshToken;
import org.os.gitbase.auth.mapper.UserMapper;
import org.os.gitbase.auth.repository.RefreshTokenRepository;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.auth.service.UserDetailService;
import org.os.gitbase.auth.service.UserService;
import org.os.gitbase.constant.Constant;
import org.os.gitbase.helper.Helper;
import org.os.gitbase.jwt.JwtTokenProvider;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.security.authentication.AuthenticationManager;

import java.time.LocalDateTime;
import java.util.*;
import java.nio.file.Paths;

import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.os.gitbase.auth.entity.enums.AuthProvider;


@Service
public class UserServiceImpl implements UserService {
    private final UserDetailService detailService;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final JwtTokenProvider jwtService;

    private final AuthenticationManager authenticationManager;
    private final RefreshTokenRepository refreshTokenRepository;

    @Autowired
    public UserServiceImpl(UserDetailService detailService, UserRepository userRepository, UserMapper userMapper,
                           JwtTokenProvider jwtService, AuthenticationManager authenticationManager,
                           RefreshTokenRepository refreshTokenRepository) {
        this.detailService = detailService;
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Override
    public UserInfo findByEmail(String email) {
        User user = userRepository.findUserByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("User not found with this email"));
        return userMapper.mapFromAuthUserToUserInfoResponse(user);
    }

    @Override
    @Transactional
    public User update(UUID id, User o) {
        return userRepository.findById(id).map((user) -> {
            user.setName(o.getName());
            user.setUpdatedAt(LocalDateTime.now());
            if (o.getProfilePictureUrl() != null && !o.getProfilePictureUrl().isBlank()) {
                user.setProfilePictureUrl(o.getProfilePictureUrl());
            }
            return userRepository.save(user);
        }).orElseThrow(() -> new EntityNotFoundException("User not found with ID : "
                + id));
    }

    @Override
    public UserInfo updateByEmail(String email, User usr) {
        return userRepository.findUserByEmail(email).map((user) -> {
            user.setName(usr.getName());
            user.setUpdatedAt(LocalDateTime.now());
            if (usr.getProfilePictureUrl() != null && !usr.getProfilePictureUrl().isBlank()) {
                user.setProfilePictureUrl(usr.getProfilePictureUrl());
            }
            userRepository.save(user);
            return userMapper.mapFromAuthUserToUserInfoResponse(user);
        }).orElseThrow(() -> new EntityNotFoundException("User not found with email : "
                + email));
    }

    @Override
    public User findById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User not found with ID: " + id));
    }

    @Override
    public User add(User o) {
        o.setCreatedAt(LocalDateTime.now());
        o.setEnabled(true);
        return userRepository.save(o);
    }

    @Override
    public User delete(UUID id) {
        User temp = userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User not found with ID: " +
                        id));
        //temp.setEnabled(false);
        userRepository.delete(temp);
        return temp;
    }

    @Override
    public List<User> readAll() {
        List<User> userList = userRepository.findAll();
        if (userList.isEmpty()) {
            throw new EntityNotFoundException("No users found");
        }
        return userList;
    }

    public UserInfo getUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String usernameFromAccessToken = null;
        
        // Handle different types of authentication principals
        if (authentication.getPrincipal() instanceof org.springframework.security.core.userdetails.UserDetails) {
            // For regular JWT authentication
            org.springframework.security.core.userdetails.UserDetails userDetail = 
                (org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal();
            usernameFromAccessToken = userDetail.getUsername();
        } else if (authentication.getPrincipal() instanceof org.springframework.security.oauth2.core.user.OAuth2User) {
            // For OAuth2 authentication (Google)
            org.springframework.security.oauth2.core.user.OAuth2User oauth2User = 
                (org.springframework.security.oauth2.core.user.OAuth2User) authentication.getPrincipal();
            
            // Try to get email from attributes first, then fallback to name
            if (oauth2User.getAttributes() != null && oauth2User.getAttributes().get("email") != null) {
                usernameFromAccessToken = (String) oauth2User.getAttributes().get("email");
            } else {
                usernameFromAccessToken = oauth2User.getName();
            }
        } else {
            // Fallback for other authentication types
            usernameFromAccessToken = authentication.getName();
        }
        
        if (usernameFromAccessToken != null) {
            Optional<User> user = userRepository.findUserByEmail(usernameFromAccessToken);
            if (user.isPresent()) {
                User u = user.get();
                String fullPath = u.getProfilePictureUrl();
                if (fullPath != null && !fullPath.startsWith("http")) {
                    // Only extract filename for local file paths
                    String filename = Paths.get(fullPath).getFileName().toString();
                    u.setProfilePictureUrl(filename);
                }
                return userMapper.mapFromAuthUserToUserInfoResponse(u);
            }
        }
        return new UserInfo();
    }

    public Map<String, ResponseCookie> authenticate(LoginDTO authRequestDTO) {
        Authentication authentication = authenticateUser(authRequestDTO);
        if (authentication.isAuthenticated()) {
            User user = detailService.loadUserByUsername(authRequestDTO.getEmail());
            if (user.getAuthProvider() == AuthProvider.GOOGLE) {
                String fullPath = user.getProfilePictureUrl();
                if (fullPath != null && !fullPath.startsWith("http")) {
                    String filename = Paths.get(fullPath).getFileName().toString();
                    user.setProfilePictureUrl(filename);
                }
            }
            user.setFullName(Helper.removeAtSymbolAndFollowing(user.getEmail()));
            user.setLastLogin(LocalDateTime.now());
            userRepository.save(user);
            List<String> roles = user.getRoles().stream().map(Role::getRoleName).toList();
            String accessToken = jwtService.generateToken(user.getUsername(), roles);
            RefreshToken refreshToken = jwtService.createRefreshToken(authRequestDTO.getEmail());
            ResponseCookie refreshTokenCookie = createRefreshTokenCookie(refreshToken.getToken());
            ResponseCookie accessTokenCookie = createAccessTokenCookie(accessToken);
            Map<String, ResponseCookie> cookies = new HashMap<>();
            cookies.put(Constant.ACCESS_TOKEN, accessTokenCookie);
            cookies.put(Constant.REFRESH_TOKEN, refreshTokenCookie);
            return cookies;
        } else {
            throw new UsernameNotFoundException("Invalid user request");
        }
    }

    public Authentication authenticateUser(LoginDTO authRequestDTO) {
        return authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(authRequestDTO.getEmail(), authRequestDTO.getPassword()));
    }

    public ResponseCookie createAccessTokenCookie(String accessToken) {
        return ResponseCookie.from(Constant.ACCESS_TOKEN,
                        accessToken)
                .httpOnly(true)
                .secure(false)
                .path("/")
                .maxAge(7200) // 2H
                .build();
    }

    public ResponseCookie createRefreshTokenCookie(String token) {
        return ResponseCookie.from(Constant.REFRESH_TOKEN,
                        token)
                .httpOnly(true)
                .secure(false)
                .path("/")
                .maxAge(86400) // 1 day
                .build();
    }

    public RefreshToken getTokenOfUserByUsername(String email) {
        return refreshTokenRepository.findRefreshTokenByEmail(email).orElseThrow(
                () -> new EntityNotFoundException("User not found"));
    }

    @Override
    public boolean existsByEmail(String email) {
        return userRepository.findUserByEmail(email).isPresent();
    }
}

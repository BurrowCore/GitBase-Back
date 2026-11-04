package org.os.gitbase.auth.service.impl;

import jakarta.persistence.EntityNotFoundException;
import org.os.gitbase.auth.dto.LoginDTO;
import org.os.gitbase.auth.dto.UserInfo;
import org.os.gitbase.auth.entity.Role;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.mapper.UserMapper;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.auth.service.UserDetailService;
import org.os.gitbase.auth.service.AuthSessionService;
import org.os.gitbase.auth.service.UserService;
import org.os.gitbase.constant.Constant;
import org.os.gitbase.helper.Helper;
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
    private final AuthenticationManager authenticationManager;
    private final AuthSessionService sessionService;

    @Autowired
    public UserServiceImpl(UserDetailService detailService, UserRepository userRepository, UserMapper userMapper,
                           AuthenticationManager authenticationManager, AuthSessionService sessionService) {
        this.detailService = detailService;
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.authenticationManager = authenticationManager;
        this.sessionService = sessionService;
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

    public AuthSessionService.IssuedTokens authenticate(LoginDTO authRequestDTO) {
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
            return sessionService.signIn(user);
        } else {
            throw new UsernameNotFoundException("Invalid user request");
        }
    }

    @Override
    public AuthSessionService.IssuedTokens signIn(String email) {
        User user = detailService.loadUserByUsername(email);
        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);
        return sessionService.signIn(user);
    }

    public Authentication authenticateUser(LoginDTO authRequestDTO) {
        return authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(authRequestDTO.getEmail(), authRequestDTO.getPassword()));
    }

    @Override
    public boolean existsByEmail(String email) {
        return userRepository.findUserByEmail(email).isPresent();
    }
}

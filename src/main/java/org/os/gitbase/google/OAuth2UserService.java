package org.os.gitbase.google;

import lombok.extern.slf4j.Slf4j;
import org.os.gitbase.auth.entity.Role;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.repository.RoleRepository;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.auth.service.UsernameAllocator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Service
@Slf4j
public class OAuth2UserService extends DefaultOAuth2UserService {
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UsernameAllocator usernameAllocator;

    public OAuth2UserService(UserRepository userRepository, RoleRepository roleRepository,
                             UsernameAllocator usernameAllocator) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.usernameAllocator = usernameAllocator;
    }
    
    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest oAuth2UserRequest) throws OAuth2AuthenticationException {
        log.info("OAuth2UserService.loadUser called for registration: {}", oAuth2UserRequest.getClientRegistration().getRegistrationId());
        
        OAuth2User oAuth2User = super.loadUser(oAuth2UserRequest);
        log.info("OAuth2User loaded: {}", oAuth2User.getName());

        try {
            OAuth2User processedUser = processOAuth2User(oAuth2UserRequest, oAuth2User);
            log.info("OAuth2User processed successfully: {}", processedUser.getName());
            return processedUser;
        } catch (Exception ex) {
            log.error("Error processing OAuth2 user", ex);
            throw new OAuth2AuthenticationException("Error processing OAuth2 user");
        }
    }
    
    private OAuth2User processOAuth2User(OAuth2UserRequest oAuth2UserRequest, OAuth2User oAuth2User) {
        GoogleOAuth2UserInfo oAuth2UserInfo = new GoogleOAuth2UserInfo(oAuth2User.getAttributes());

        if (oAuth2UserInfo.getEmail() == null || oAuth2UserInfo.getEmail().isEmpty()) {
            throw new OAuth2AuthenticationException("Email not found from OAuth2 provider");
        }

        User user = userRepository.findUserByEmail(oAuth2UserInfo.getEmail())
                .map(existingUser -> updateExistingUser(existingUser, oAuth2UserInfo))
                // orElseGet: orElse would evaluate (and attempt to insert) a new user on every login.
                .orElseGet(() -> registerNewUser(oAuth2UserInfo));

        return UserPrincipal.create(user, oAuth2User.getAttributes());
    }
    
    private User registerNewUser(GoogleOAuth2UserInfo oAuth2UserInfo) {
        try {
            User user = new User(
                    // Username = unique namespace derived from the email, never the display name
                    // (which has spaces and is not unique).
                    usernameAllocator.allocate(oAuth2UserInfo.getEmail()),
                    oAuth2UserInfo.getName(),
                    oAuth2UserInfo.getEmail(),
                    oAuth2UserInfo.getId(),
                    oAuth2UserInfo.getImageUrl()
            );
            
            // Set additional properties
            user.setAuthProvider(org.os.gitbase.auth.entity.enums.AuthProvider.GOOGLE);
            user.setEmailVerified(true);
            user.setEnabled(true);
            user.setLastLogin(LocalDateTime.now());
            
            // Assign default USER role
            Set<Role> roles = new HashSet<>();
            Role userRole = roleRepository.findRoleByRoleName("ROLE_USER")
                    .orElseThrow(() -> new RuntimeException("Default USER role not found. Please ensure the DataInitializer has run."));
            roles.add(userRole);
            user.setRoles(roles);

            log.info("Registering new OAuth2 user: {} with role: {}", user.getEmail(), userRole.getRoleName());
            User savedUser = userRepository.save(user);
            log.info("Successfully saved OAuth2 user: {} with ID: {}", savedUser.getEmail(), savedUser.getId());
            return savedUser;
        } catch (Exception e) {
            log.error("Error registering new OAuth2 user: {}", oAuth2UserInfo.getEmail(), e);
            throw new RuntimeException("Failed to register OAuth2 user: " + e.getMessage(), e);
        }
    }

    private User updateExistingUser(User existingUser, GoogleOAuth2UserInfo oAuth2UserInfo) {
        try {
            // Refresh the display name only. The username is the repository namespace and must not
            // change on login — overwriting it renamed users and orphaned their repositories.
            existingUser.setFullName(oAuth2UserInfo.getName());
            existingUser.setProfilePictureUrl(oAuth2UserInfo.getImageUrl());
            existingUser.setLastLogin(LocalDateTime.now());
            
            // Update auth provider if not set
            if (existingUser.getAuthProvider() == null) {
                existingUser.setAuthProvider(org.os.gitbase.auth.entity.enums.AuthProvider.GOOGLE);
            }
            
            // Ensure user has at least USER role
            if (existingUser.getRoles().isEmpty()) {
                Role userRole = roleRepository.findRoleByRoleName("ROLE_USER")
                        .orElseThrow(() -> new RuntimeException("Default USER role not found. Please ensure the DataInitializer has run."));
                existingUser.getRoles().add(userRole);
                log.info("Added ROLE_USER to existing user: {}", existingUser.getEmail());
            }
            
            log.info("Updating existing OAuth2 user: {}", existingUser.getEmail());
            User savedUser = userRepository.save(existingUser);
            log.info("Successfully updated OAuth2 user: {} with ID: {}", savedUser.getEmail(), savedUser.getId());
            return savedUser;
        } catch (Exception e) {
            log.error("Error updating existing OAuth2 user: {}", existingUser.getEmail(), e);
            throw new RuntimeException("Failed to update OAuth2 user: " + e.getMessage(), e);
        }
    }
}

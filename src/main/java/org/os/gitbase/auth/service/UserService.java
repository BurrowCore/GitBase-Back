package org.os.gitbase.auth.service;

import org.os.gitbase.auth.dto.LoginDTO;
import org.os.gitbase.auth.dto.UserInfo;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.entity.jwt.RefreshToken;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface UserService {
    UserInfo findByEmail(String email);
    User update(UUID id, User o);
    UserInfo updateByEmail(String email,User user);
    User findById(UUID id);

    User add(User o);

    User delete(UUID id);

    List<User> readAll();

    UserInfo getUser();

    Map<String, ResponseCookie> authenticate(LoginDTO authRequestDTO);

    Authentication authenticateUser(LoginDTO authRequestDTO);

    ResponseCookie createAccessTokenCookie(String accessToken);

    ResponseCookie createRefreshTokenCookie(String token);

    RefreshToken getTokenOfUserByUsername(String username);

    boolean existsByEmail(String email);

}

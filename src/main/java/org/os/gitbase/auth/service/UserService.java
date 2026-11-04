package org.os.gitbase.auth.service;

import org.os.gitbase.auth.dto.LoginDTO;
import org.os.gitbase.auth.dto.UserInfo;
import org.os.gitbase.auth.entity.User;
import org.springframework.security.core.Authentication;

import java.util.List;
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

    /** Checks the credentials and opens a sign-in session. */
    AuthSessionService.IssuedTokens authenticate(LoginDTO authRequestDTO);

    /** Opens a sign-in session for an already-verified identity (e.g. a Google ID token). */
    AuthSessionService.IssuedTokens signIn(String email);

    Authentication authenticateUser(LoginDTO authRequestDTO);




    boolean existsByEmail(String email);

}

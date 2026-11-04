package org.os.gitbase.auth;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.service.AuthSessionService;
import org.os.gitbase.constant.Constant;
import org.os.gitbase.jwt.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The reported bug, through the real security filter chain: after sign-out, the access and refresh
 * tokens the browser held must be refused — not merely deleted from the browser.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SignOutEndToEndTest {

    private static final String PROFILE = Constant.AUTH_MAPPING_REQUEST + Constant.PROFILE_INFO_MAPPING_REQUEST;
    private static final String LOGOUT = Constant.AUTH_MAPPING_REQUEST + Constant.LOGOUT_MAPPING_REQUEST;
    private static final String REFRESH = Constant.AUTH_MAPPING_REQUEST + Constant.REFRESH_TOKEN_MAPPING_REQUEST;

    @Autowired private MockMvc mvc;
    @Autowired private AuthSessionService sessions;
    @Autowired private JwtTokenProvider jwt;
    @PersistenceContext private EntityManager em;

    private Cookie access;
    private Cookie refresh;
    private String username;

    @BeforeEach
    void signIn() {
        User user = new User();
        user.setName("e2e-" + UUID.randomUUID().toString().substring(0, 8));
        user.setEmail(user.getName() + "@example.test");
        em.persist(user);
        em.flush();
        username = user.getUsername();
        var tokens = sessions.signIn(user);
        access = new Cookie(Constant.ACCESS_TOKEN, tokens.accessToken());
        refresh = new Cookie(Constant.REFRESH_TOKEN, tokens.refreshToken());
    }

    @Test
    void tokensKeptAfterSignOutAreRefused() throws Exception {
        mvc.perform(get(PROFILE).cookie(access)).andExpect(status().isOk());

        mvc.perform(post(LOGOUT).cookie(access, refresh))
                .andExpect(status().isOk())
                .andExpect(header().stringValues("Set-Cookie",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString(Constant.ACCESS_TOKEN + "=;"))));

        // A copy of the cookies taken before sign-out no longer works.
        mvc.perform(get(PROFILE).cookie(access)).andExpect(status().isUnauthorized());
        mvc.perform(post(REFRESH).cookie(refresh)).andExpect(status().isUnauthorized());
    }

    @Test
    void signOutWithOnlyTheAccessTokenStillEndsTheSession() throws Exception {
        mvc.perform(post(LOGOUT).cookie(access)).andExpect(status().isOk());

        mvc.perform(get(PROFILE).cookie(access)).andExpect(status().isUnauthorized());
        mvc.perform(post(REFRESH).cookie(refresh)).andExpect(status().isUnauthorized());
    }

    @Test
    void theAccessTokenSentAsBearerIsNotAcceptedEither() throws Exception {
        mvc.perform(post(LOGOUT).cookie(access, refresh)).andExpect(status().isOk());

        mvc.perform(get(PROFILE).header("Authorization", "Bearer " + access.getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aMalformedAccessCookieIsUnauthorizedNotAServerError() throws Exception {
        mvc.perform(get(PROFILE).cookie(new Cookie(Constant.ACCESS_TOKEN, "not.a.jwt")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshSetsTheNewCookieOnTheAuthPathOnly() throws Exception {
        mvc.perform(post(REFRESH).cookie(refresh))
                .andExpect(status().isOk())
                .andExpect(header().stringValues("Set-Cookie",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("Path=/api/v1/auth"))));
    }

    @Test
    void anAccessTokenForASessionThatDoesNotExistIsRefused() throws Exception {
        String forged = jwt.generateToken(username, List.of("ROLE_USER"), UUID.randomUUID(), Duration.ofMinutes(5));
        mvc.perform(get(PROFILE).cookie(new Cookie(Constant.ACCESS_TOKEN, forged)))
                .andExpect(status().isUnauthorized());
    }
}

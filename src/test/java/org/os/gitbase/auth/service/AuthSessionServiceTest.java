package org.os.gitbase.auth.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.jwt.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sessions, rotation and revocation against PostgreSQL, with a clock the test moves.
 * Needs PostgreSQL and {@code PRIV_KEY}/{@code PUB_KEY}, like {@code GitbaseApplicationTests}.
 */
@SpringBootTest
@Transactional
class AuthSessionServiceTest {

    /** A clock tests can move forward. */
    static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-04T10:00:00Z");

        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        MovableClock testClock() {
            return new MovableClock();
        }
    }

    @Autowired private AuthSessionService sessions;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private MovableClock clock;
    @PersistenceContext private EntityManager em;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setName("sess-" + UUID.randomUUID().toString().substring(0, 8));
        user.setEmail(user.getName() + "@example.test");
        em.persist(user);
        em.flush();
    }

    private UUID sid(AuthSessionService.IssuedTokens tokens) {
        return jwt.extractSessionId(tokens.accessToken()).orElseThrow();
    }

    @Test
    void signInOpensASessionThatTheAccessTokenNames() {
        var tokens = sessions.signIn(user);

        assertThat(sessions.isActive(sid(tokens))).isTrue();
        assertThat(jwt.extractUsername(tokens.accessToken())).isEqualTo(user.getEmail());
    }

    @Test
    void refreshRotates_andTheNewTokenKeepsWorking() {
        var first = sessions.signIn(user);
        var second = sessions.refresh(first.refreshToken());
        var third = sessions.refresh(second.refreshToken());

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(sid(third)).isEqualTo(sid(first));
        assertThat(sessions.isActive(sid(third))).isTrue();
    }

    @Test
    void reusingARotatedTokenAfterTheGraceRevokesTheWholeSession() {
        var first = sessions.signIn(user);
        var second = sessions.refresh(first.refreshToken());
        clock.advance(AuthSessionService.REUSE_GRACE.plusSeconds(1));

        assertThatThrownBy(() -> sessions.refresh(first.refreshToken()))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(sessions.isActive(sid(first))).isFalse();
        // The legitimate holder's newer token dies with it — the session is compromised.
        assertThatThrownBy(() -> sessions.refresh(second.refreshToken()))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void twoTabsRefreshingAtOnceDoNotSignTheUserOut() {
        var first = sessions.signIn(user);
        sessions.refresh(first.refreshToken());
        clock.advance(Duration.ofSeconds(2));

        var again = sessions.refresh(first.refreshToken());

        assertThat(sessions.isActive(sid(first))).isTrue();
        assertThat(sid(again)).isEqualTo(sid(first));
        // Only an access token: a replay inside the window must not get a refresh chain of its own.
        assertThat(again.refreshToken()).isNull();
    }

    @Test
    void signOutEndsTheSessionForEveryToken() {
        var tokens = sessions.signIn(user);

        sessions.signOutByRefreshToken(tokens.refreshToken());

        assertThat(sessions.isActive(sid(tokens))).isFalse();
        assertThatThrownBy(() -> sessions.refresh(tokens.refreshToken()))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void signOutBySessionIdWorksFromTheAccessTokenAlone() {
        var tokens = sessions.signIn(user);
        sessions.signOutBySessionId(sid(tokens));
        assertThat(sessions.isActive(sid(tokens))).isFalse();
    }

    @Test
    void anExpiredRefreshTokenIsRefused() {
        var tokens = sessions.signIn(user);
        clock.advance(AuthSessionService.REFRESH_TOKEN_TTL.plusMinutes(1));

        assertThatThrownBy(() -> sessions.refresh(tokens.refreshToken()))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void aSessionEndsAtItsMaximumAgeHoweverOftenItIsRefreshed() {
        var tokens = sessions.signIn(user);
        for (int day = 0; day < 29; day++) {
            clock.advance(Duration.ofDays(1));
            tokens = sessions.refresh(tokens.refreshToken());
        }
        clock.advance(Duration.ofDays(2));
        String last = tokens.refreshToken();

        assertThatThrownBy(() -> sessions.refresh(last)).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void unknownAndBlankTokensAreRefused_andSignOutIgnoresThem() {
        assertThatThrownBy(() -> sessions.refresh("not-a-token")).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> sessions.refresh("")).isInstanceOf(BadCredentialsException.class);
        assertThatCode(() -> sessions.signOutByRefreshToken("not-a-token")).doesNotThrowAnyException();
    }

    @Test
    void onlyAHashOfTheRefreshTokenIsStored() {
        var tokens = sessions.signIn(user);
        em.flush();
        Long plain = em.createQuery("SELECT COUNT(t) FROM RefreshToken t WHERE t.tokenHash = :v", Long.class)
                .setParameter("v", tokens.refreshToken()).getSingleResult();
        assertThat(plain).isZero();
    }
}

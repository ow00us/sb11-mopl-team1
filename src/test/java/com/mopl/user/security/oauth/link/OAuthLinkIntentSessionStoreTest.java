package com.mopl.user.security.oauth.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.user.config.OAuthLinkProperties;
import com.mopl.user.entity.OAuthProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class OAuthLinkIntentSessionStoreTest {

    private static final Instant NOW =
        Instant.parse("2026-08-24T01:00:00Z");

    private static final UUID USER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    OAuthLinkProperties properties;
    OAuthLinkIntentSessionStore store;

    @BeforeEach
    void setUp() {
        properties =
            new OAuthLinkProperties();

        properties.setIntentExpiration(
            Duration.ofMinutes(5)
        );

        store =
            createStoreAt(NOW);
    }

    @Test
    @DisplayName("OAuth 연결 의도를 설정된 만료 시각과 함께 저장한다")
    void save_storesIntentWithExpiration() {
        // given
        MockHttpServletRequest request =
            new MockHttpServletRequest();

        // when
        OAuthLinkIntent saved =
            store.save(
                request,
                USER_ID,
                OAuthProvider.GOOGLE
            );

        // then
        assertThat(saved.userId())
            .isEqualTo(USER_ID);

        assertThat(saved.provider())
            .isEqualTo(OAuthProvider.GOOGLE);

        assertThat(saved.expiresAt())
            .isEqualTo(
                NOW.plus(
                    Duration.ofMinutes(5)
                )
            );

        assertThat(
            request.getSession(false)
        ).isNotNull();
    }

    @Test
    @DisplayName("저장한 OAuth 연결 의도는 한 번만 소비할 수 있다")
    void consume_returnsIntentOnlyOnce() {
        // given
        MockHttpServletRequest request =
            new MockHttpServletRequest();

        store.save(
            request,
            USER_ID,
            OAuthProvider.KAKAO
        );

        // when
        Optional<OAuthLinkIntent> first =
            store.consume(
                request,
                OAuthProvider.KAKAO
            );

        Optional<OAuthLinkIntent> second =
            store.consume(
                request,
                OAuthProvider.KAKAO
            );

        // then
        assertThat(first)
            .isPresent();

        assertThat(first.orElseThrow().userId())
            .isEqualTo(USER_ID);

        assertThat(second)
            .isEmpty();
    }

    @Test
    @DisplayName("Provider가 다른 Callback은 연결 의도를 사용할 수 없다")
    void consume_rejectsDifferentProvider() {
        // given
        MockHttpServletRequest request =
            new MockHttpServletRequest();

        store.save(
            request,
            USER_ID,
            OAuthProvider.GOOGLE
        );

        // when
        Optional<OAuthLinkIntent> result =
            store.consume(
                request,
                OAuthProvider.NAVER
            );

        // then
        assertThat(result)
            .isEmpty();

        /*
         * Provider가 달랐던 요청도 다시 사용할 수 없어야 한다.
         */
        assertThat(
            store.consume(
                request,
                OAuthProvider.GOOGLE
            )
        ).isEmpty();
    }

    @Test
    @DisplayName("만료된 OAuth 연결 의도는 소비할 수 없다")
    void consume_rejectsExpiredIntent() {
        // given
        MockHttpServletRequest request =
            new MockHttpServletRequest();

        store.save(
            request,
            USER_ID,
            OAuthProvider.GOOGLE
        );

        OAuthLinkIntentSessionStore expiredStore =
            createStoreAt(
                NOW.plus(
                    Duration.ofMinutes(5)
                )
            );

        // when
        Optional<OAuthLinkIntent> result =
            expiredStore.consume(
                request,
                OAuthProvider.GOOGLE
            );

        // then
        assertThat(result)
            .isEmpty();
    }

    @Test
    @DisplayName("세션이 없으면 새 세션을 만들지 않고 빈 결과를 반환한다")
    void consume_doesNotCreateSession_whenSessionIsMissing() {
        // given
        MockHttpServletRequest request =
            new MockHttpServletRequest();

        // when
        Optional<OAuthLinkIntent> result =
            store.consume(
                request,
                OAuthProvider.GOOGLE
            );

        // then
        assertThat(result)
            .isEmpty();

        assertThat(
            request.getSession(false)
        ).isNull();
    }

    @Test
    @DisplayName("저장된 OAuth 연결 의도가 있으면 존재 여부를 반환한다")
    void hasPendingIntent_returnsTrue_whenIntentExists() {
        // given
        MockHttpServletRequest request =
            new MockHttpServletRequest();

        store.save(
            request,
            USER_ID,
            OAuthProvider.GOOGLE
        );

        // when
        boolean result =
            store.hasPendingIntent(request);

        // then
        assertThat(result)
            .isTrue();
    }

    @Test
    @DisplayName("세션이 없으면 연결 의도가 없다고 반환하고 세션을 생성하지 않는다")
    void hasPendingIntent_doesNotCreateSession() {
        // given
        MockHttpServletRequest request =
            new MockHttpServletRequest();

        // when
        boolean result =
            store.hasPendingIntent(request);

        // then
        assertThat(result)
            .isFalse();

        assertThat(
            request.getSession(false)
        ).isNull();
    }

    @Test
    @DisplayName("OAuth 인증 실패 시 저장된 연결 의도를 제거한다")
    void clear_removesStoredIntent() {
        // given
        MockHttpServletRequest request =
            new MockHttpServletRequest();

        store.save(
            request,
            USER_ID,
            OAuthProvider.NAVER
        );

        // when
        store.clear(request);

        // then
        assertThat(
            store.consume(
                request,
                OAuthProvider.NAVER
            )
        ).isEmpty();
    }

    private OAuthLinkIntentSessionStore createStoreAt(
        Instant instant
    ) {
        return new OAuthLinkIntentSessionStore(
            properties,
            Clock.fixed(
                instant,
                ZoneOffset.UTC
            )
        );
    }
}

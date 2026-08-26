package com.mopl.user.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * 인가 요청 저장소의 다중 인스턴스 동작을 검증합니다.
 *
 * <p>컨텍스트 하나가 곧 인스턴스 하나입니다. 두 인스턴스 사이의 동작을 확인하려면 저장소
 * 객체를 두 개 만들어 서로 다른 인스턴스로 두고, 하나가 저장한 것을 다른 하나가 찾게 합니다.
 *
 * <p>Redis 없이 돌립니다. 확인하려는 것은 "저장소를 공유하면 인스턴스가 달라도 찾는다"는
 * 성질이고, 그 성질은 저장 매체와 무관합니다. 실제 Redis 연동은
 * {@link RedisOAuth2AuthorizationRequestStoreIntegrationTest} 가 확인합니다.
 */
class OAuth2AuthorizationRequestRepositoryTest {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String STATE = "state-abc-123";

    /** 두 인스턴스가 함께 보는 저장소입니다. */
    private final InMemoryStore sharedStore = new InMemoryStore();

    private AuthorizationRequestRepository<OAuth2AuthorizationRequest> instance() {
        return new RedisOAuth2AuthorizationRequestRepository(sharedStore, TTL);
    }

    private static OAuth2AuthorizationRequest authorizationRequest(String state) {
        return OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
            .clientId("mopl-client")
            .redirectUri("https://mopl.example.com/login/oauth2/code/google")
            .scopes(Set.of("openid", "profile"))
            .state(state)
            .attributes(attributes -> attributes.put("registration_id", "google"))
            .additionalParameters(Map.of("code_challenge_method", "S256"))
            .build();
    }

    private static MockHttpServletRequest callbackWith(String state) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/login/oauth2/code/google");
        if (state != null) {
            request.setParameter("state", state);
        }
        return request;
    }

    /**
     * 지금 기본값이 왜 2인스턴스에서 깨지는지를 고정합니다.
     *
     * <p>이 테스트가 통과한다는 것은 문제가 실재한다는 뜻입니다. 나중에 누군가 저장소를 다시
     * 세션 기반으로 되돌리면 아래 공유 저장소 테스트가 실패해 그 사실이 드러납니다.
     */
    @Nested
    @DisplayName("기본 HTTP 세션 저장소")
    class HttpSessionRepository {

        private final HttpSessionOAuth2AuthorizationRequestRepository repository =
            new HttpSessionOAuth2AuthorizationRequestRepository();

        @Test
        @DisplayName("같은 인스턴스에서는 저장한 인가 요청을 찾는다")
        void findsRequestWithinSameSession() {
            MockHttpServletRequest authorize = new MockHttpServletRequest();
            repository.saveAuthorizationRequest(
                authorizationRequest(STATE), authorize, new MockHttpServletResponse());

            MockHttpServletRequest callback = callbackWith(STATE);
            // 같은 인스턴스로 돌아온 상황입니다. 세션을 그대로 물려줍니다.
            callback.setSession(authorize.getSession());

            assertThat(repository.loadAuthorizationRequest(callback)).isNotNull();
        }

        /**
         * 인가를 시작한 인스턴스와 callback 을 받은 인스턴스가 다른 상황입니다. 세션이
         * 인스턴스 로컬이므로 저장한 요청을 찾지 못합니다. 백엔드가 둘이면 절반 확률로
         * 이 경로에 들어갑니다.
         */
        @Test
        @DisplayName("다른 인스턴스로 callback이 오면 인가 요청을 찾지 못한다")
        void losesRequestAcrossInstances() {
            MockHttpServletRequest authorize = new MockHttpServletRequest();
            repository.saveAuthorizationRequest(
                authorizationRequest(STATE), authorize, new MockHttpServletResponse());

            // 세션을 물려주지 않습니다. 다른 인스턴스가 받은 요청입니다.
            MockHttpServletRequest callback = callbackWith(STATE);

            assertThat(repository.loadAuthorizationRequest(callback)).isNull();
        }
    }

    @Nested
    @DisplayName("공유 저장소")
    class SharedRepository {

        @Test
        @DisplayName("인스턴스 A가 저장한 인가 요청을 인스턴스 B가 찾는다")
        void findsRequestAcrossInstances() {
            instance().saveAuthorizationRequest(
                authorizationRequest(STATE), new MockHttpServletRequest(),
                new MockHttpServletResponse());

            OAuth2AuthorizationRequest loaded =
                instance().loadAuthorizationRequest(callbackWith(STATE));

            assertThat(loaded).isNotNull();
            assertThat(loaded.getState()).isEqualTo(STATE);
            assertThat(loaded.getClientId()).isEqualTo("mopl-client");
            assertThat(loaded.getRedirectUri())
                .isEqualTo("https://mopl.example.com/login/oauth2/code/google");
            assertThat(loaded.<String>getAttribute("registration_id")).isEqualTo("google");
        }

        @Test
        @DisplayName("인스턴스 A가 저장한 인가 요청을 인스턴스 B가 소비한다")
        void consumesRequestAcrossInstances() {
            instance().saveAuthorizationRequest(
                authorizationRequest(STATE), new MockHttpServletRequest(),
                new MockHttpServletResponse());

            OAuth2AuthorizationRequest removed = instance().removeAuthorizationRequest(
                callbackWith(STATE), new MockHttpServletResponse());

            assertThat(removed).isNotNull();
            assertThat(removed.getState()).isEqualTo(STATE);
        }

        /**
         * 같은 인가 코드를 두 번 쓰려는 시도입니다. 첫 소비에서 지우므로 두 번째는 찾지
         * 못하고, Spring Security 가 인증 실패로 처리합니다.
         */
        @Test
        @DisplayName("소비한 인가 요청은 다시 쓸 수 없다")
        void rejectsReplay() {
            instance().saveAuthorizationRequest(
                authorizationRequest(STATE), new MockHttpServletRequest(),
                new MockHttpServletResponse());

            assertThat(instance().removeAuthorizationRequest(
                callbackWith(STATE), new MockHttpServletResponse())).isNotNull();
            assertThat(instance().removeAuthorizationRequest(
                callbackWith(STATE), new MockHttpServletResponse())).isNull();
        }

        @Test
        @DisplayName("state가 없는 요청은 인가 요청을 돌려주지 않는다")
        void rejectsMissingState() {
            instance().saveAuthorizationRequest(
                authorizationRequest(STATE), new MockHttpServletRequest(),
                new MockHttpServletResponse());

            assertThat(instance().loadAuthorizationRequest(callbackWith(null))).isNull();
        }

        @Test
        @DisplayName("모르는 state는 인가 요청을 돌려주지 않는다")
        void rejectsUnknownState() {
            instance().saveAuthorizationRequest(
                authorizationRequest(STATE), new MockHttpServletRequest(),
                new MockHttpServletResponse());

            assertThat(instance().loadAuthorizationRequest(callbackWith("tampered"))).isNull();
        }

        /**
         * 저장한 값이 깨져 있어도 인증 실패로 끝나야 합니다. 예외를 던지면 인증 실패가 500
         * 오류로 바뀝니다.
         */
        @Test
        @DisplayName("읽을 수 없는 값이 저장되어 있으면 인가 요청을 돌려주지 않는다")
        void rejectsCorruptedValue() {
            sharedStore.values.put("auth:oauth2:authorization-request:" + STATE, "not-json");

            assertThat(instance().loadAuthorizationRequest(callbackWith(STATE))).isNull();
        }

        /**
         * Spring Security 는 요청을 지울 때 {@code save} 를 {@code null} 로 부릅니다. 그
         * 규약을 지키지 않으면 지워야 할 항목이 TTL 이 끝날 때까지 남습니다.
         */
        @Test
        @DisplayName("null로 저장하면 기존 인가 요청을 지운다")
        void nullSaveRemovesRequest() {
            instance().saveAuthorizationRequest(
                authorizationRequest(STATE), new MockHttpServletRequest(),
                new MockHttpServletResponse());

            instance().saveAuthorizationRequest(
                null, callbackWith(STATE), new MockHttpServletResponse());

            assertThat(instance().loadAuthorizationRequest(callbackWith(STATE))).isNull();
        }

        @Test
        @DisplayName("보관 기간을 저장소에 그대로 넘긴다")
        void passesTimeToLive() {
            instance().saveAuthorizationRequest(
                authorizationRequest(STATE), new MockHttpServletRequest(),
                new MockHttpServletResponse());

            assertThat(sharedStore.timeToLive).isEqualTo(TTL);
        }

        /**
         * 저장한 값에 client secret 이나 access token 이 섞이면 Redis 를 읽을 수 있는 사람이
         * 그 값을 함께 얻습니다.
         */
        @Test
        @DisplayName("저장한 값에 client secret과 access token이 없다")
        void doesNotStoreCredentials() {
            instance().saveAuthorizationRequest(
                authorizationRequest(STATE), new MockHttpServletRequest(),
                new MockHttpServletResponse());

            String stored = sharedStore.values.get("auth:oauth2:authorization-request:" + STATE);

            assertThat(stored)
                .doesNotContain("client_secret")
                .doesNotContain("clientSecret")
                .doesNotContain("access_token")
                .doesNotContain("accessToken");
        }

        @Test
        @DisplayName("state가 없는 인가 요청은 저장을 거부한다")
        void rejectsSavingRequestWithoutState() {
            OAuth2AuthorizationRequest withoutState = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("mopl-client")
                .redirectUri("https://mopl.example.com/login/oauth2/code/google")
                .build();

            assertThatThrownBy(() -> instance().saveAuthorizationRequest(
                withoutState, new MockHttpServletRequest(), new MockHttpServletResponse()))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** 두 인스턴스가 함께 보는 저장소를 흉내 냅니다. */
    private static class InMemoryStore implements OAuth2AuthorizationRequestStore {

        private final Map<String, String> values = new HashMap<>();
        private Duration timeToLive;

        @Override
        public void save(String key, String value, Duration timeToLive) {
            values.put(key, value);
            this.timeToLive = timeToLive;
        }

        @Override
        public String find(String key) {
            return values.get(key);
        }

        @Override
        public String findAndRemove(String key) {
            return values.remove(key);
        }
    }
}

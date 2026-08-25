package com.mopl.user.security.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.client.jackson2.OAuth2ClientJackson2Module;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.util.StringUtils;

/**
 * OAuth2 인가 요청을 Redis 에 두어 인스턴스 사이에서 공유합니다.
 *
 * <p>Spring Security 기본 구현은 인가 요청을 HTTP 세션에 둡니다. 세션은 인스턴스 로컬이므로,
 * 인가를 시작한 인스턴스와 Provider 가 callback 을 보낸 인스턴스가 다르면 저장한 요청을 찾지
 * 못하고 로그인이 실패합니다. 백엔드를 두 개 띄우면 절반 확률로 그렇게 됩니다.
 *
 * <p>로드밸런서 세션 고정으로 막을 수도 있지만 그러면 인증 경로 하나 때문에 나머지 요청의
 * 분산까지 묶입니다. 상태를 공유하는 쪽이 인스턴스 수와 무관하게 성립합니다.
 *
 * <p>키는 {@code state} 입니다. Spring Security 가 만들어 Provider 로 보내고 callback 에
 * 그대로 돌아오는 값이라, callback 을 받은 인스턴스가 저장 위치를 알아낼 유일한 단서입니다.
 *
 * <p>짧은 TTL 을 둡니다. 사용자가 Provider 화면에서 로그인을 끝내지 않고 떠나면 그 요청은
 * 영영 소비되지 않습니다. 만료가 없으면 그런 항목이 계속 쌓입니다.
 *
 * <p>callback 에서 꺼낼 때 원자적으로 지웁니다. 같은 {@code state} 로 두 번째 요청이 오면
 * 찾지 못하고 실패합니다. 인가 코드 재사용 시도를 여기서 끊습니다.
 *
 * <p>저장하는 값에 client secret 이나 access token 은 들어가지 않습니다.
 * {@link OAuth2AuthorizationRequest} 는 인가 요청을 다시 만들기 위한 값만 담습니다. PKCE
 * code verifier 는 들어가지만 이 값은 서버에만 있어야 하는 값이고, 그래서 클라이언트가 아니라
 * Redis 에 둡니다.
 */
@Slf4j
public class RedisOAuth2AuthorizationRequestRepository
    implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    /**
     * 인가 요청 Redis Key 접두어입니다.
     *
     * <p>최종 Key 예시: {@code auth:oauth2:authorization-request:{state}}
     */
    private static final String KEY_PREFIX = "auth:oauth2:authorization-request:";

    private final OAuth2AuthorizationRequestStore store;
    private final ObjectMapper objectMapper;
    private final Duration timeToLive;

    public RedisOAuth2AuthorizationRequestRepository(
        OAuth2AuthorizationRequestStore store, Duration timeToLive
    ) {
        this.store = store;
        this.timeToLive = timeToLive;
        this.objectMapper = createObjectMapper();
    }

    /**
     * 인가 요청 직렬화 전용 {@link ObjectMapper} 를 따로 만듭니다.
     *
     * <p>애플리케이션 공용 ObjectMapper 를 쓰지 않습니다. Spring Security 의 Jackson 모듈은
     * 허용 타입 목록과 전용 mixin 을 등록하는데, 그 설정이 공용 ObjectMapper 로 새어 나가면
     * 관계없는 도메인 직렬화까지 영향을 받습니다.
     */
    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModules(
            SecurityJackson2Modules.getModules(
                RedisOAuth2AuthorizationRequestRepository.class.getClassLoader()));
        mapper.registerModule(new OAuth2ClientJackson2Module());
        return mapper;
    }

    /**
     * callback 요청의 {@code state} 로 인가 요청을 찾습니다.
     *
     * <p>여기서는 지우지 않습니다. Spring Security 가 이 메서드를 검증 과정에서 부르고,
     * 실제 소비는 {@link #removeAuthorizationRequest} 에서 일어납니다.
     */
    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        String state = stateOf(request);
        if (state == null) {
            return null;
        }
        return deserialize(store.find(keyOf(state)), state);
    }

    /**
     * 인가 요청을 저장합니다.
     *
     * <p>Spring Security 는 요청을 지울 때도 이 메서드를 {@code null} 로 부릅니다. 그 규약을
     * 지키지 않으면 지워야 할 항목이 TTL 이 끝날 때까지 남습니다.
     */
    @Override
    public void saveAuthorizationRequest(
        OAuth2AuthorizationRequest authorizationRequest,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        if (authorizationRequest == null) {
            removeAuthorizationRequest(request, response);
            return;
        }

        String state = authorizationRequest.getState();
        if (!StringUtils.hasText(state)) {
            // state 가 없으면 callback 에서 찾을 방법이 없습니다. 저장해도 소용이 없고,
            // 그 사실이 로그인 실패로만 드러나면 원인을 찾기 어렵습니다.
            throw new IllegalArgumentException("인가 요청에 state 가 없습니다.");
        }

        try {
            store.save(keyOf(state), objectMapper.writeValueAsString(authorizationRequest),
                timeToLive);
        } catch (Exception e) {
            // 저장하지 못했으면 이어지는 callback 이 반드시 실패합니다. 그때가 아니라
            // 여기서 원인을 드러냅니다.
            throw new IllegalStateException("OAuth2 인가 요청을 저장하지 못했습니다.", e);
        }
    }

    /**
     * 인가 요청을 꺼내면서 지웁니다.
     *
     * <p>조회와 삭제를 한 번에 합니다. 나눠서 하면 같은 {@code state} 로 동시에 들어온 두
     * 요청이 모두 같은 인가 요청을 받아 갑니다.
     */
    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(
        HttpServletRequest request, HttpServletResponse response
    ) {
        String state = stateOf(request);
        if (state == null) {
            return null;
        }
        return deserialize(store.findAndRemove(keyOf(state)), state);
    }

    private String stateOf(HttpServletRequest request) {
        String state = request.getParameter(OAuth2ParameterNames.STATE);
        return StringUtils.hasText(state) ? state : null;
    }

    private String keyOf(String state) {
        return KEY_PREFIX + state;
    }

    /**
     * 저장해 둔 값을 인가 요청으로 되돌립니다.
     *
     * <p>읽지 못하면 예외를 던지지 않고 {@code null} 을 돌려줍니다. 없는 것과 깨진 것 모두
     * "이 요청을 신뢰할 수 없다"로 같고, Spring Security 가 그때 인증 실패로 처리합니다.
     * 여기서 예외를 던지면 인증 실패가 500 오류로 바뀝니다.
     */
    private OAuth2AuthorizationRequest deserialize(String value, String state) {
        if (value == null) {
            return null;
        }

        try {
            return objectMapper.readValue(value, OAuth2AuthorizationRequest.class);
        } catch (Exception e) {
            // state 는 Provider 를 거쳐 돌아온 값이라 로그에 그대로 남기지 않습니다.
            log.warn("저장된 OAuth2 인가 요청을 읽지 못했습니다. stateLength={}", state.length(), e);
            return null;
        }
    }
}

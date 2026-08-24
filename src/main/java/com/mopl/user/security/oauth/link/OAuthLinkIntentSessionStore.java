package com.mopl.user.security.oauth.link;

import com.mopl.user.config.OAuthLinkProperties;
import com.mopl.user.entity.OAuthProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * OAuth 계정 연결 의도를 임시 HTTP 세션에 저장하는 컴포넌트
 *
 * <p>Spring Security OAuth2 로그인도 Authorization Request를
 * OAuth 왕복 동안 임시 HTTP 세션에 보관합니다. 연결 의도 역시 같은
 * 브라우저의 임시 OAuth 트랜잭션 범위에서만 유지합니다.</p>
 *
 * <p>세션은 로그인 인증 상태를 저장하는 용도로 사용하지 않습니다.
 * 실제 API 인증은 기존과 동일하게 JWT Access Token으로 처리합니다.</p>
 */
@Component
public class OAuthLinkIntentSessionStore {

    /**
     * 다른 세션 속성과 충돌하지 않는 내부 전용 속성 이름
     */
    private static final String ATTRIBUTE_NAME =
        OAuthLinkIntentSessionStore.class.getName()
            + ".INTENT";

    private final OAuthLinkProperties properties;
    private final Clock clock;

    /**
     * 운영 환경에서는 UTC 시스템 시각을 사용
     */
    @Autowired
    public OAuthLinkIntentSessionStore(
        OAuthLinkProperties properties
    ) {
        this(
            properties,
            Clock.systemUTC()
        );
    }

    /**
     * 테스트에서 고정 Clock을 주입하기 위한 생성자
     */
    OAuthLinkIntentSessionStore(
        OAuthLinkProperties properties,
        Clock clock
    ) {
        this.properties =
            Objects.requireNonNull(properties);

        this.clock =
            Objects.requireNonNull(clock);
    }

    /**
     * 새 OAuth 연결 의도를 현재 브라우저 세션에 저장
     *
     * <p>이미 다른 연결 의도가 존재하면 가장 최근 요청으로 교체합니다.
     * 한 브라우저에서 여러 Provider 연결을 동시에 진행해 의도가 서로
     * 뒤섞이는 상황을 방지하기 위함입니다.</p>
     *
     * @param request 현재 HTTP 요청
     * @param userId 연결 대상 사용자 UUID
     * @param provider 연결할 OAuth Provider
     * @return 저장된 OAuth 연결 의도
     */
    public OAuthLinkIntent save(
        HttpServletRequest request,
        UUID userId,
        OAuthProvider provider
    ) {
        Objects.requireNonNull(
            request,
            "HTTP 요청은 필수입니다."
        );

        Instant expiresAt =
            clock.instant()
                .plus(
                    properties
                        .getIntentExpiration()
                );

        OAuthLinkIntent intent =
            new OAuthLinkIntent(
                userId,
                provider,
                expiresAt
            );

        request
            .getSession(true)
            .setAttribute(
                ATTRIBUTE_NAME,
                intent
            );

        return intent;
    }

    /**
     * 현재 세션의 OAuth 연결 의도를 한 번만 소비
     *
     * <p>세션 속성은 검증 전에 먼저 제거합니다. 만료됐거나 Provider가
     * 일치하지 않는 요청도 재사용할 수 없도록 하기 위함입니다.</p>
     *
     * <p>세션이 없으면 새 세션을 생성하지 않습니다.</p>
     *
     * @param request OAuth Callback HTTP 요청
     * @param expectedProvider Callback을 처리 중인 OAuth Provider
     * @return 유효한 연결 의도, 없거나 만료됐으면 빈 Optional
     */
    public Optional<OAuthLinkIntent> consume(
        HttpServletRequest request,
        OAuthProvider expectedProvider
    ) {
        Objects.requireNonNull(
            request,
            "HTTP 요청은 필수입니다."
        );

        Objects.requireNonNull(
            expectedProvider,
            "OAuth Provider는 필수입니다."
        );

        HttpSession session =
            request.getSession(false);

        if (session == null) {
            return Optional.empty();
        }

        Object stored =
            session.getAttribute(
                ATTRIBUTE_NAME
            );

        /*
         * 성공·실패 여부와 무관하게 일회성으로 소비
         */
        session.removeAttribute(
            ATTRIBUTE_NAME
        );

        if (!(stored instanceof OAuthLinkIntent intent)) {
            return Optional.empty();
        }

        if (intent.provider()
            != expectedProvider) {
            return Optional.empty();
        }

        if (intent.isExpired(
            clock.instant()
        )) {
            return Optional.empty();
        }

        return Optional.of(intent);
    }

    /**
     * OAuth 인증 실패 또는 취소 시 남아 있는 연결 의도를 제거
     *
     * @param request 현재 HTTP 요청
     */
    public void clear(
        HttpServletRequest request
    ) {
        Objects.requireNonNull(
            request,
            "HTTP 요청은 필수입니다."
        );

        HttpSession session =
            request.getSession(false);

        if (session != null) {
            session.removeAttribute(
                ATTRIBUTE_NAME
            );
        }
    }
}

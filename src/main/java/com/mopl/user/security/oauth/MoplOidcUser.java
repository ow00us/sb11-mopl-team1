package com.mopl.user.security.oauth;

import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.UserRole;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * OIDC Provider의 인증 결과를 모두의 플리 공통 인증 형식으로 변환한 Principal
 *
 * <p>Google처럼 OIDC를 지원하는 Provider는 일반 OAuth2 사용자 정보뿐 아니라
 * 서명된 ID Token을 통해 사용자 신원을 전달합니다.</p>
 *
 * <p>{@link MoplOAuth2User}를 상속하므로 기존 OAuth 인증 성공 Handler가
 * Provider 종류와 무관하게 사용자 UUID를 이용하여 모두의 플리
 * Refresh Token을 발급할 수 있습니다.</p>
 */
public class MoplOidcUser
    extends MoplOAuth2User
    implements OidcUser {

    /**
     * Provider가 발급한 OIDC ID Token
     *
     * <p>Spring Security가 서명, issuer, audience, 만료 시간을 검증한
     * 결과만 이 Principal에 전달합니다.</p>
     */
    private final OidcIdToken idToken;

    /**
     * OIDC UserInfo Endpoint에서 조회한 사용자 정보
     *
     * <p>UserInfo 요청이 수행되지 않은 경우 null일 수 있습니다.</p>
     */
    private final OidcUserInfo userInfo;

    /**
     * 모두의 플리 OIDC Principal을 생성합니다.
     *
     * @param userId         모두의 플리 사용자 UUID
     * @param email          모두의 플리에 저장된 정규화된 이메일
     * @param role           모두의 플리 사용자 권한
     * @param provider       OIDC 인증 Provider
     * @param providerUserId Provider의 고유 사용자 식별자
     * @param attributes     검증된 OIDC 사용자 attributes
     * @param idToken        검증된 OIDC ID Token
     * @param userInfo       OIDC UserInfo, 없으면 null
     */
    public MoplOidcUser(
        UUID userId,
        String email,
        UserRole role,
        OAuthProvider provider,
        String providerUserId,
        Map<String, Object> attributes,
        OidcIdToken idToken,
        OidcUserInfo userInfo
    ) {
        super(
            userId,
            email,
            role,
            provider,
            providerUserId,
            attributes
        );

        if (idToken == null) {
            throw new IllegalArgumentException(
                "OIDC ID Token은 필수입니다."
            );
        }

        this.idToken = idToken;
        this.userInfo = userInfo;
    }

    /**
     * OIDC ID Token과 UserInfo에서 구성된 검증된 Claims를 반환
     *
     * @return 수정할 수 없는 OIDC Claims
     */
    @Override
    public Map<String, Object> getClaims() {
        return getAttributes();
    }

    /**
     * 검증된 OIDC ID Token을 반환
     */
    @Override
    public OidcIdToken getIdToken() {
        return idToken;
    }

    /**
     * OIDC UserInfo Endpoint의 사용자 정보를 반환
     *
     * @return UserInfo 또는 조회되지 않은 경우 null
     */
    @Override
    public OidcUserInfo getUserInfo() {
        return userInfo;
    }
}

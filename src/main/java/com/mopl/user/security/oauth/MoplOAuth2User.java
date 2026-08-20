package com.mopl.user.security.oauth;

import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.UserRole;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Provider별 OAuth 사용자 정보를 모두의 플리 인증 형식으로 변환한 공통 Principal
 *
 * <p>Google, Kakao, Naver는 서로 다른 사용자 정보 응답 구조를 사용하지만,
 * OAuth 인증 성공 이후의 Refresh Token 발급과 Redirect 처리는
 * Provider별 응답 구조를 알 필요가 없습니다.</p>
 *
 * <p>Provider별 OAuth2UserService는 외부 사용자 정보를 해석한 뒤
 * 모두의 플리 사용자를 조회하거나 생성하고, 이 Principal에 필요한
 * 공통 사용자 정보만 전달합니다.</p>
 *
 * <p>비밀번호 해시와 OAuth Provider의 Access Token 및 Refresh Token은
 * Principal에 보관하지 않습니다.</p>
 */
@Getter
public class MoplOAuth2User implements OAuth2User {

    /**
     * 모두의 플리 사용자 UUID
     *
     * <p>OAuth 인증 성공 Handler는 이 값을 사용하여
     * 모두의 플리 Refresh Token 세션을 생성합니다.</p>
     */
    private final UUID userId;

    /**
     * 모두의 플리에 저장된 정규화된 사용자 이메일
     */
    private final String email;

    /**
     * 모두의 플리 사용자 권한
     */
    private final UserRole role;

    /**
     * 사용자를 인증한 OAuth Provider
     */
    private final OAuthProvider provider;

    /**
     * OAuth Provider가 발급한 변경되지 않는 사용자 고유 식별자
     */
    private final String providerUserId;

    /**
     * Provider가 반환한 원본 사용자 attributes
     *
     * <p>외부에서 전달받은 Map을 그대로 보관하면 호출자가 Map을 수정하여
     * 인증 이후 Principal의 내용이 변경될 수 있으므로 복사 후
     * 읽기 전용 Map으로 보관합니다.</p>
     */
    private final Map<String, Object> attributes;

    /**
     * OAuth 인증 사용자 Principal을 생성합니다.
     *
     * @param userId         모두의 플리 사용자 UUID
     * @param email          모두의 플리에 저장된 정규화된 이메일
     * @param role           모두의 플리 사용자 권한
     * @param provider       OAuth 인증 Provider
     * @param providerUserId Provider가 발급한 사용자 고유 식별자
     * @param attributes     Provider가 반환한 원본 사용자 정보
     * @throws IllegalArgumentException 필수 사용자 정보가 유효하지 않은 경우
     */
    public MoplOAuth2User(
        UUID userId,
        String email,
        UserRole role,
        OAuthProvider provider,
        String providerUserId,
        Map<String, Object> attributes
    ) {
        if (userId == null) {
            throw new IllegalArgumentException(
                "OAuth 인증 사용자 UUID는 필수입니다."
            );
        }

        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException(
                "OAuth 인증 사용자 이메일은 필수입니다."
            );
        }

        if (role == null) {
            throw new IllegalArgumentException(
                "OAuth 인증 사용자 권한은 필수입니다."
            );
        }

        if (provider == null) {
            throw new IllegalArgumentException(
                "OAuth 인증 Provider는 필수입니다."
            );
        }

        if (providerUserId == null || providerUserId.isBlank()) {
            throw new IllegalArgumentException(
                "OAuth Provider 사용자 ID는 필수입니다."
            );
        }

        if (attributes == null) {
            throw new IllegalArgumentException(
                "OAuth 사용자 attributes는 필수입니다."
            );
        }

        this.userId = userId;
        this.email = email;
        this.role = role;
        this.provider = provider;
        this.providerUserId = providerUserId;

        /*
         * Map.copyOf()는 null 값이 포함된 Provider 응답을 거부하므로
         * null 값을 포함할 수 있는 외부 응답과의 호환성을 위해
         * LinkedHashMap으로 복사한 뒤 읽기 전용 Map으로 감싼다.
         */
        this.attributes =
            Collections.unmodifiableMap(
                new LinkedHashMap<>(attributes)
            );
    }

    /**
     * 모두의 플리 역할을 Spring Security 권한 형식으로 변환
     *
     * @return ROLE_USER 또는 ROLE_ADMIN 권한
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(
            new SimpleGrantedAuthority(
                "ROLE_" + role.name()
            )
        );
    }

    /**
     * Spring Security 내부에서 OAuth 인증 사용자를 구분할 고유 이름을 반환
     *
     * <p>Provider 사용자 ID만 사용하면 서로 다른 Provider에서 같은 ID가
     * 발급됐을 때 충돌할 수 있으므로 Provider와 사용자 ID를 함께 사용합니다.</p>
     *
     * @return 예: GOOGLE:google-user-id
     */
    @Override
    public String getName() {
        return provider.name()
            + ":"
            + providerUserId;
    }
}

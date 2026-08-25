package com.mopl.user.dto;

import com.mopl.user.entity.OAuthAccount;
import com.mopl.user.entity.OAuthProvider;
import java.time.Instant;

/**
 * 사용자에게 연결된 OAuth 계정 정보를 반환하는 응답 DTO
 *
 * <p>Provider Access Token, Refresh Token 및 Provider 사용자 ID는
 * 외부에 공개하지 않습니다. 클라이언트가 계정 관리 화면을 구성하는 데
 * 필요한 Provider와 연결 시각만 반환합니다.</p>
 *
 * @param provider OAuth 인증 제공자
 * @param connectedAt OAuth 계정이 연결된 시각
 */
public record OAuthAccountDto(
    OAuthProvider provider,
    Instant connectedAt
) {

    /**
     * OAuthAccount 엔티티를 공개 응답 DTO로 변환
     *
     * @param oauthAccount 변환할 OAuth 계정 연결 정보
     * @return 공개 가능한 OAuth 계정 연결 정보
     */
    public static OAuthAccountDto from(
        OAuthAccount oauthAccount
    ) {
        return new OAuthAccountDto(
            oauthAccount.getProvider(),
            oauthAccount.getCreatedAt()
        );
    }
}

package com.mopl.user.security.oauth.link;

import com.mopl.user.entity.OAuthProvider;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 로그인된 사용자가 특정 OAuth Provider 계정 연결을 시작했다는 임시 정보
 *
 * <p>OAuth 인증이 완료될 때 한 번만 소비하며, 만료된 연결 의도는
 * 계정 연결에 사용할 수 없습니다.</p>
 *
 * @param userId 연결 대상 MOPL 사용자 UUID
 * @param provider 연결할 OAuth Provider
 * @param expiresAt 연결 의도의 절대 만료 시각
 */
public record OAuthLinkIntent(
    UUID userId,
    OAuthProvider provider,
    Instant expiresAt
) implements Serializable {

    public OAuthLinkIntent {
        Objects.requireNonNull(
            userId,
            "OAuth 연결 대상 사용자 ID는 필수입니다."
        );

        Objects.requireNonNull(
            provider,
            "OAuth 연결 Provider는 필수입니다."
        );

        Objects.requireNonNull(
            expiresAt,
            "OAuth 연결 의도 만료 시각은 필수입니다."
        );
    }

    /**
     * 지정된 시각을 기준으로 연결 의도가 만료됐는지 확인
     *
     * @param now 현재 시각
     * @return 만료됐거나 만료 시각과 정확히 같으면 true
     */
    public boolean isExpired(
        Instant now
    ) {
        Objects.requireNonNull(
            now,
            "현재 시각은 필수입니다."
        );

        return !expiresAt.isAfter(now);
    }
}

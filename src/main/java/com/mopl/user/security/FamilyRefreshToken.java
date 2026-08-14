package com.mopl.user.security;

import java.util.UUID;

/**
 * 하나의 로그인 세션 Family에 속한 Refresh Token 발급 결과
 *
 * <p>familyId는 로그인 시 생성되고 해당 로그인 세션이 유지되는 동안
 * Refresh Token Rotation 이후에도 변경되지 않습니다.</p>
 *
 * <p>rawToken은 브라우저의 HttpOnly Cookie에 전달할 Refresh Token
 * 원문입니다. 서버 저장소에는 원문을 저장하지 않고 SHA-256 해시만
 * 저장해야 합니다.</p>
 *
 * @param familyId 로그인 세션을 식별하는 안정적인 UUID
 * @param rawToken 클라이언트 Cookie에 전달할 Refresh Token 원문
 */
public record FamilyRefreshToken(
    UUID familyId,
    String rawToken
) {

    /**
     * 잘못된 Family Refresh Token 객체가 생성되지 않도록
     * 필수 값을 생성 시점에 검증
     */
    public FamilyRefreshToken {
        if (familyId == null) {
            throw new IllegalArgumentException(
                "Refresh Token Family ID는 null일 수 없습니다."
            );
        }

        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException(
                "Refresh Token 원문은 비어 있을 수 없습니다."
            );
        }
    }
}

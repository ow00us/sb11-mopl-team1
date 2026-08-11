package com.mopl.user.storage;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Refresh Token 세션 저장소가 제공해야 하는 기능을 정의
 *
 * 이 인터페이스는 Redis와 같은 구체적인 저장 기술을 노출하지 않는다.
 * RefreshTokenService는 이 인터페이스만 의존하며,
 * 실제 저장 방식은 구현체가 담당
 *
 * 이를 통해 인증 Service의 발급 정책과 Redis 데이터 접근 코드를 분리
 */
public interface RefreshTokenStore {

    /**
     * Refresh Token 세션을 저장
     *
     * 저장소에는 Refresh Token 원문이 아닌 SHA-256 해시만 전달
     * expiration은 Redis Key에 적용할 TTL로 사용
     *
     * @param userId Refresh Token을 발급받은 사용자 UUID
     * @param tokenHash Refresh Token 원문의 SHA-256 해시
     * @param expiration Refresh Token 세션의 유효 기간
     */
    void save(
        UUID userId,
        String tokenHash,
        Duration expiration
    );

    /**
     * Refresh Token 해시로 세션 소유 사용자 UUID를 조회
     *
     * Redis에서 Key가 만료되거나 삭제됐다면 빈 Optional을 반환
     *
     * @param tokenHash 조회할 Refresh Token SHA-256 해시
     * @return 세션 소유 사용자 UUID, 존재하지 않으면 빈 Optional
     */
    Optional<UUID> findUserIdByTokenHash(String tokenHash);

    /**
     * 특정 사용자가 보유한 Refresh Token 해시 목록을 조회
     *
     * 비밀번호 변경, 계정 잠금 및 전체 로그아웃에서
     * 사용자의 모든 Refresh Token 세션을 제거할 때 사용
     *
     * @param userId 조회할 사용자 UUID
     * @return 사용자가 보유한 Refresh Token 해시 집합
     */
    Set<String> findTokenHashesByUserId(UUID userId);
}

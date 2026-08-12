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
     * 기존 Refresh Token 세션을 새로운 세션으로 원자적으로 교체
     *
     * <p>기존 세션 확인, 기존 세션 삭제와 새로운 세션 저장을 하나의
     * Redis 연산으로 수행합니다. 따라서 동일한 Refresh Token으로
     * 동시에 재발급을 요청하더라도 하나의 요청만 성공할 수 있습니다.</p>
     *
     * <p>기존 Refresh Token 세션이 존재하지 않거나 해당 사용자의
     * 세션이 아니면 아무 값도 변경하지 않고 false를 반환합니다.</p>
     *
     * @param userId Refresh Token 세션의 사용자 UUID
     * @param oldTokenHash 재발급에 사용된 기존 Refresh Token 해시
     * @param newTokenHash 새로 발급할 Refresh Token 해시
     * @param expiration 새로운 Refresh Token 세션의 유효 기간
     * @return 교체에 성공하면 true, 기존 세션이 유효하지 않으면 false
     */
    boolean rotate(
        UUID userId,
        String oldTokenHash,
        String newTokenHash,
        Duration expiration
    );

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

package com.mopl.user.storage;

import java.time.Duration;
import java.util.UUID;
import java.util.Optional;

/**
 * 이미 발급된 Access Token의 사용자 인증을 즉시 차단하는 저장소
 */
public interface AccessTokenBlockStore {

    /**
     * 사용자의 기존 Access Token을 차단
     *
     * @param userId 차단할 사용자 UUID
     * @param expiration 차단 상태 유지 시간
     */
    void block(
        UUID userId,
        Duration expiration
    );

    /**
     * Redis에 저장된 사용자의 Access Token 차단 상태를 조회
     *
     * @return true는 차단, false는 허용, empty는 캐시되지 않은 상태
     */
    Optional<Boolean> findBlocked(
        UUID userId
    );

    /**
     * DB에서 확인한 인증 허용 상태를 Redis 키가 없을 때만 저장
     *
     * <p>동시에 계정 차단이 진행되어 BLOCKED 값이 저장된 경우
     * 허용 상태가 이를 덮어쓰지 않도록 원자적인 SETNX를 사용합니다.</p>
     *
     * @param userId 허용 상태를 저장할 사용자 UUID
     * @param expiration 허용 상태 유지 시간
     */
    void allowIfAbsent(
        UUID userId,
        Duration expiration
    );

    /**
     * 데이터베이스 탈퇴 처리 실패 시 선행 생성한 차단 상태를 제거
     *
     * @param userId 차단을 해제할 사용자 UUID
     */
    void unblock(
        UUID userId
    );
}

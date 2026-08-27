package com.mopl.user.storage;

import java.time.Duration;
import java.util.UUID;

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
     * 사용자가 Access Token 차단 상태인지 확인
     *
     * @param userId 확인할 사용자 UUID
     * @return 차단 상태이면 true
     */
    boolean isBlocked(
        UUID userId
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

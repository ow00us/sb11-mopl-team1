package com.mopl.user.storage;

import java.time.Duration;
import java.util.UUID;

/**
 * OAuth 전용 사용자의 로컬 로그인 수단 추가에 필요한
 * 이메일 인증 상태 저장소
 *
 * <p>인증 코드 원문이 아닌 HMAC 해시만 저장하며,
 * 구현체는 발급 제한과 인증 코드 일회성 소비를
 * 동시 요청에서도 원자적으로 처리해야 합니다.</p>
 */
public interface EmailVerificationStore {

    /**
     * 이메일 인증 상태를 저장
     *
     * <p>같은 사용자의 재전송 제한이 활성화되어 있으면 기존 인증 상태를
     * 덮어쓰지 않고 {@link EmailVerificationIssueResult#COOLDOWN_ACTIVE}를
     * 반환해야 합니다.</p>
     *
     * @param userId 인증을 요청한 사용자 UUID
     * @param normalizedEmail 인증할 정규화된 이메일
     * @param codeHash 인증 코드 원문의 HMAC-SHA256 해시
     * @param verificationExpiration 인증 상태의 유효 시간
     * @param resendCooldown 같은 사용자의 재전송 제한 시간
     * @return 인증 상태 저장 결과
     */
    EmailVerificationIssueResult issue(
        UUID userId,
        String normalizedEmail,
        String codeHash,
        Duration verificationExpiration,
        Duration resendCooldown
    );

    /**
     * 인증 코드 검증 결과를 반영하고 성공한 인증 상태를 한 번만 소비
     *
     * <p>이메일 또는 인증 코드 해시가 일치하지 않으면 실패 횟수를
     * 원자적으로 증가시킵니다. 실패 횟수가 최대 허용 횟수에 도달하면
     * 인증 상태를 즉시 제거해야 합니다.</p>
     *
     * <p>인증에 성공하면 인증 상태를 즉시 제거하여 동일한 코드가
     * 두 번 사용되지 않도록 해야 합니다.</p>
     *
     * @param userId 인증을 요청한 사용자 UUID
     * @param normalizedEmail 사용자가 제출한 정규화된 이메일
     * @param candidateCodeHash 사용자가 제출한 인증 코드의 HMAC 해시
     * @param maxAttempts 최대 검증 실패 허용 횟수
     * @return 인증 상태 검증 및 소비 결과
     */
    EmailVerificationConsumeResult consume(
        UUID userId,
        String normalizedEmail,
        String candidateCodeHash,
        int maxAttempts
    );

    /**
     * 현재 저장된 인증 코드 해시가 전달된 해시와 일치할 때만
     * 인증 상태와 재전송 제한을 제거
     *
     * <p>Redis 저장 이후 이메일 발송이 실패했을 때 사용합니다.
     * 메일 발송이 지연되는 동안 더 새로운 인증 코드가 발급됐다면
     * 새로운 인증 상태는 삭제하지 않습니다.</p>
     *
     * @param userId 인증 상태 소유 사용자 UUID
     * @param expectedCodeHash 삭제하려는 인증 코드의 HMAC 해시
     * @return 일치하는 인증 상태를 삭제했으면 true
     */
    boolean deleteIfCodeHashMatches(
        UUID userId,
        String expectedCodeHash
    );

    /**
     * 사용자의 남아 있는 이메일 인증 상태를 제거
     *
     * <p>로컬 로그인 수단 추가가 완료되거나 계정 상태가 변경되어
     * 진행 중인 인증을 더 이상 허용하면 안 될 때 사용합니다.</p>
     *
     * @param userId 인증 상태를 제거할 사용자 UUID
     */
    void deleteByUserId(
        UUID userId
    );
}

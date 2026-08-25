package com.mopl.user.storage;

/**
 * 이메일 인증 코드 발급 상태 저장 결과
 */
public enum EmailVerificationIssueResult {

    /**
     * 새 인증 상태와 재전송 제한이 정상적으로 저장됨
     */
    ISSUED,

    /**
     * 재전송 제한 시간이 아직 지나지 않아 발급하지 않음
     */
    COOLDOWN_ACTIVE
}

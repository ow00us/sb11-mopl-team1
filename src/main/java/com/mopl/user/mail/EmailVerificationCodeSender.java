package com.mopl.user.mail;

import java.time.Duration;

/**
 * OAuth 전용 사용자의 로컬 로그인 수단 추가에 필요한
 * 이메일 인증 코드를 발송하는 기능의 경계
 */
public interface EmailVerificationCodeSender {

    /**
     * 이메일 소유권 확인용 인증 코드를 발송
     *
     * <p>인증 코드 원문은 이메일 발송에만 사용하며 로그 또는
     * 별도 저장소에 기록해서는 안 됩니다.</p>
     *
     * @param recipientEmail 인증 코드를 받을 실제 이메일
     * @param verificationCode 발송할 6자리 인증 코드 원문
     * @param expiration 인증 코드 유효 시간
     */
    void send(
        String recipientEmail,
        String verificationCode,
        Duration expiration
    );
}

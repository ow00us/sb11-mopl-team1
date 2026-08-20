package com.mopl.user.mail;

/**
 * 비밀번호 초기화로 생성된 임시 비밀번호를 사용자 이메일로 발송하는 기능의 경계
 *
 * <p>비밀번호 초기화 서비스가 Spring Mail, SMTP와 같은 구체적인 메일 발송 기술에
 * 직접 의존하지 않도록 인터페이스로 분리합니다. 서비스는 임시 비밀번호를 어디로
 * 보내야 하는지만 요청하고, 실제 메일 작성과 SMTP 전송은 구현체가 담당합니다.</p>
 *
 * <p>이 구조를 사용하면 단위 테스트에서 실제 이메일을 보내지 않고 Mock 구현으로
 * 발송 요청만 검증할 수 있습니다. 향후 SMTP 대신 AWS SES나 다른 메일 서비스로
 * 변경하더라도 비밀번호 초기화 서비스 코드는 수정하지 않아도 됩니다.</p>
 */
public interface TemporaryPasswordEmailSender {

    /**
     * 사용자에게 임시 비밀번호 안내 이메일을 발송
     *
     * <p>temporaryPassword는 사용자가 로그인할 수 있는 원문 임시 비밀번호이므로
     * 구현체는 이를 로그에 출력하거나 별도 저장소에 보관해서는 안 됩니다.</p>
     *
     * @param recipientEmail 임시 비밀번호 이메일을 받을 사용자 이메일
     * @param temporaryPassword 이메일 본문으로 전달할 임시 비밀번호 원문
     */
    void send(
        String recipientEmail,
        String temporaryPassword
    );
}

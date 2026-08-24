package com.mopl.user.service;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.config.OAuthLocalCredentialProperties;
import com.mopl.user.dto.LocalCredentialEmailVerificationRequest;
import com.mopl.user.entity.User;
import com.mopl.user.mail.EmailVerificationCodeSender;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.security.EmailVerificationCodeGenerator;
import com.mopl.user.security.EmailVerificationCodeHasher;
import com.mopl.user.storage.EmailVerificationIssueResult;
import com.mopl.user.storage.EmailVerificationStore;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * OAuth 전용 사용자가 이메일·비밀번호 로그인 수단을
 * 추가하는 과정을 처리하는 서비스
 */
@Service
@RequiredArgsConstructor
public class OAuthLocalCredentialService {

    /**
     * OAuth 전용 사용자에게 내부 식별용으로 부여하는 예약 이메일 suffix
     */
    private static final String OAUTH_INTERNAL_EMAIL_SUFFIX =
        "@oauth.invalid";

    private final UserRepository userRepository;
    private final EmailVerificationCodeGenerator
        verificationCodeGenerator;
    private final EmailVerificationCodeHasher
        verificationCodeHasher;
    private final EmailVerificationStore
        verificationStore;
    private final EmailVerificationCodeSender
        verificationCodeSender;
    private final OAuthLocalCredentialProperties
        properties;

    /**
     * 로컬 로그인 ID로 사용할 이메일에 인증 코드를 발송
     *
     * <p>DB 조회 이후 Redis와 SMTP 외부 I/O를 수행하므로 메서드 전체에
     * DB 트랜잭션을 열지 않습니다. SMTP 응답을 기다리는 동안 DB 커넥션을
     * 점유하지 않도록 하기 위함입니다.</p>
     *
     * @param authenticatedUserId JWT 인증 사용자 UUID
     * @param userId 로컬 로그인 수단을 추가할 사용자 UUID
     * @param request 인증할 이메일 요청
     */
    public void sendVerificationCode(
        UUID authenticatedUserId,
        UUID userId,
        LocalCredentialEmailVerificationRequest request
    ) {
        validateSelf(
            authenticatedUserId,
            userId
        );

        String normalizedEmail =
            normalizeEmail(
                request.email()
            );

        /*
         * OAuth 전용 사용자 내부 식별 이메일 도메인을 실제 로그인 ID로
         * 등록하지 못하도록 차단
         */
        if (isOAuthInternalEmail(
            normalizedEmail
        )) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        User user =
            userRepository.findById(userId)
                .orElseThrow(() ->
                    new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND
                    )
                );

        if (user.isLocked()) {
            throw new BusinessException(
                ErrorCode.FORBIDDEN
            );
        }

        /*
         * passwordHash가 이미 존재하면 로컬 로그인이 가능한 사용자
         * 기존 로그인 이메일 변경 API로 오용하지 못하도록 거부
         */
        if (user.getPasswordHash() != null) {
            throw new BusinessException(
                ErrorCode.LOCAL_CREDENTIAL_ALREADY_EXISTS
            );
        }

        /*
         * 기존 사용자와 동일한 이메일을 자동 병합하지 않는다.
         * 이메일 소유권을 확인하더라도 계정 병합은 별도의 강한 인증
         * 정책이 필요한 작업이므로 현재 요청은 충돌로 거부
         */
        if (userRepository.existsByEmail(
            normalizedEmail
        )) {
            throw new BusinessException(
                ErrorCode.EMAIL_DUPLICATE
            );
        }

        String verificationCode =
            verificationCodeGenerator.generate();

        String codeHash =
            verificationCodeHasher.hash(
                userId,
                normalizedEmail,
                verificationCode
            );

        EmailVerificationIssueResult issueResult =
            verificationStore.issue(
                userId,
                normalizedEmail,
                codeHash,
                properties.getVerificationExpiration(),
                properties.getResendCooldown()
            );

        if (
            issueResult
                == EmailVerificationIssueResult.COOLDOWN_ACTIVE
        ) {
            throw new BusinessException(
                ErrorCode.EMAIL_VERIFICATION_COOLDOWN
            );
        }

        try {
            verificationCodeSender.send(
                normalizedEmail,
                verificationCode,
                properties.getVerificationExpiration()
            );
        } catch (RuntimeException mailException) {
            /*
             * Redis 저장 후 SMTP 발송에 실패하면 사용자는 전달받지 못한
             * 코드 때문에 재전송 제한에 걸릴 수 있다.
             *
             * 현재 저장된 해시가 이번 요청의 해시와 같을 때만 삭제하여
             * 메일 발송 지연 중 생성된 더 새로운 인증 상태를 보호
             */
            try {
                verificationStore
                    .deleteIfCodeHashMatches(
                        userId,
                        codeHash
                    );
            } catch (RuntimeException compensationException) {
                /*
                 * 보상 삭제 실패가 실제 SMTP 실패를 덮어쓰지 않도록
                 * suppressed exception으로 보존
                 */
                mailException.addSuppressed(
                    compensationException
                );
            }

            throw mailException;
        }
    }

    private void validateSelf(
        UUID authenticatedUserId,
        UUID userId
    ) {
        if (authenticatedUserId == null) {
            throw new BusinessException(
                ErrorCode.UNAUTHORIZED
            );
        }

        if (
            userId == null
                || !authenticatedUserId.equals(userId)
        ) {
            throw new BusinessException(
                ErrorCode.FORBIDDEN
            );
        }
    }

    private String normalizeEmail(
        String email
    ) {
        return email
            .strip()
            .toLowerCase(
                Locale.ROOT
            );
    }

    private boolean isOAuthInternalEmail(
        String email
    ) {
        return email.endsWith(
            OAUTH_INTERNAL_EMAIL_SUFFIX
        );
    }
}

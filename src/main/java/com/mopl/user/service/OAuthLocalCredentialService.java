package com.mopl.user.service;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.config.OAuthLocalCredentialProperties;
import com.mopl.user.dto.LocalCredentialEmailVerificationRequest;
import com.mopl.user.dto.LocalCredentialRegistrationRequest;
import com.mopl.user.entity.User;
import com.mopl.user.mail.EmailVerificationCodeSender;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.security.EmailVerificationCodeGenerator;
import com.mopl.user.security.EmailVerificationCodeHasher;
import com.mopl.user.storage.EmailVerificationConsumeResult;
import com.mopl.user.storage.EmailVerificationIssueResult;
import com.mopl.user.storage.EmailVerificationStore;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    private final EmailVerificationCodeGenerator verificationCodeGenerator;
    private final EmailVerificationCodeHasher verificationCodeHasher;
    private final EmailVerificationStore verificationStore;
    private final EmailVerificationCodeSender verificationCodeSender;
    private final PasswordEncoder passwordEncoder;
    private final OAuthLocalCredentialRegistrationService registrationService;
    private final OAuthLocalCredentialProperties properties;

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

        validateRequestedEmail(
            normalizedEmail
        );

        validateRegistrationCandidate(
            userId,
            normalizedEmail
        );

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

    /**
     * 인증된 이메일과 새 비밀번호를 로컬 로그인 수단으로 등록
     *
     * <p>인증 코드 검증과 비밀번호 인코딩은 DB 트랜잭션 밖에서 수행하고,
     * 실제 사용자 행 변경은 별도의 트랜잭션 서비스에 위임합니다.</p>
     *
     * @param authenticatedUserId JWT 인증 사용자 UUID
     * @param userId 로컬 로그인 수단을 추가할 사용자 UUID
     * @param request 인증 코드와 새 비밀번호 요청
     */
    public void registerLocalCredential(
        UUID authenticatedUserId,
        UUID userId,
        LocalCredentialRegistrationRequest request
    ) {
        validateSelf(
            authenticatedUserId,
            userId
        );

        String normalizedEmail =
            normalizeEmail(
                request.email()
            );

        validateRequestedEmail(
            normalizedEmail
        );

        /*
         * 인증 코드 소비 전에 현재 계정 상태를 먼저 검사
         * 이미 잠겼거나 로컬 로그인이 추가된 요청 때문에 일회성 코드를
         * 불필요하게 소비하지 않도록 한다.
         *
         * 실제 DB 변경 직전에는 트랜잭션 서비스가 쓰기 잠금과 함께
         * 동일한 조건을 다시 검사
         */
        validateRegistrationCandidate(
            userId,
            normalizedEmail
        );

        String candidateCodeHash =
            verificationCodeHasher.hash(
                userId,
                normalizedEmail,
                request.verificationCode()
            );

        EmailVerificationConsumeResult consumeResult =
            verificationStore.consume(
                userId,
                normalizedEmail,
                candidateCodeHash,
                properties.getMaxAttempts()
            );

        /*
         * 상태 없음, 잘못된 코드, 시도 횟수 초과를 모두 같은 오류로
         * 반환하여 인증 상태의 구체적인 정보를 노출하지 않는다.
         */
        if (
            consumeResult
                != EmailVerificationConsumeResult.VERIFIED
        ) {
            throw new BusinessException(
                ErrorCode.EMAIL_VERIFICATION_INVALID
            );
        }

        /*
         * 올바른 인증 코드를 확인한 뒤에만 비용이 큰 BCrypt 연산을
         * 수행하여 잘못된 코드 요청이 CPU 자원을 불필요하게 사용하지 않도록 한다.
         */
        String encodedPassword =
            passwordEncoder.encode(
                request.password()
            );

        registrationService.register(
            userId,
            normalizedEmail,
            encodedPassword
        );
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

    /**
     * OAuth 내부 식별용 예약 이메일을 실제 로그인 ID로 사용할 수 없도록 검증
     */
    private void validateRequestedEmail(
        String normalizedEmail
    ) {
        if (isOAuthInternalEmail(
            normalizedEmail
        )) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }
    }

    /**
     * 인증 코드 발급 및 소비 전 현재 사용자가 로컬 로그인 수단을
     * 추가할 수 있는 상태인지 검증
     */
    private void validateRegistrationCandidate(
        UUID userId,
        String normalizedEmail
    ) {
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

        if (user.getPasswordHash() != null) {
            throw new BusinessException(
                ErrorCode.LOCAL_CREDENTIAL_ALREADY_EXISTS
            );
        }

        if (userRepository.existsByEmail(
            normalizedEmail
        )) {
            throw new BusinessException(
                ErrorCode.EMAIL_DUPLICATE
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

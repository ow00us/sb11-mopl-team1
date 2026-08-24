package com.mopl.user.service;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.storage.RefreshTokenStore;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OAuth 전용 사용자의 실제 이메일과 비밀번호 해시를
 * 짧은 DB 트랜잭션 안에서 등록
 */
@Service
@RequiredArgsConstructor
public class OAuthLocalCredentialRegistrationService {

    private final UserRepository userRepository;
    private final RefreshTokenStore refreshTokenStore;

    /**
     * 이메일 인증이 완료된 사용자에게 로컬 로그인 수단을 등록
     *
     * <p>같은 사용자의 동시 등록 요청을 직렬화하기 위해 사용자 행을
     * 쓰기 잠금으로 조회합니다. 사전 중복 조회 이후 다른 트랜잭션이
     * 같은 이메일을 저장하는 경쟁 조건은 DB 고유 제약과 flush로
     * 최종 방어합니다.</p>
     *
     * <p>이메일과 비밀번호가 변경되면 기존 인증 상태를 더 이상
     * 신뢰하지 않으므로 사용자의 모든 Refresh Token Family를
     * 폐기합니다.</p>
     *
     * @param userId 로컬 로그인 수단을 등록할 사용자 UUID
     * @param normalizedEmail 소유권 인증을 마친 정규화된 이메일
     * @param encodedPassword PasswordEncoder로 생성한 비밀번호 해시
     */
    @Transactional
    public void register(
        UUID userId,
        String normalizedEmail,
        String encodedPassword
    ) {
        validateArguments(
            userId,
            normalizedEmail,
            encodedPassword
        );

        /*
         * 동일 사용자의 동시 등록 요청을 직렬화
         * 첫 번째 요청이 로컬 비밀번호를 등록하면 두 번째 요청은
         * 아래 passwordHash 검사에서 거부
         */
        User user =
            userRepository
                .findByIdForUpdate(userId)
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

        if (
            !normalizedEmail.equals(
                user.getEmail()
            )
                && userRepository.existsByEmail(
                normalizedEmail
            )
        ) {
            throw new BusinessException(
                ErrorCode.EMAIL_DUPLICATE
            );
        }

        user.registerLocalCredential(
            normalizedEmail,
            encodedPassword
        );

        try {
            /*
             * 트랜잭션 종료까지 기다리지 않고 이메일 고유 제약 오류를
             * 현재 서비스 경계에서 확인하여 안정적인 409 오류로 변환
             */
            userRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(
                ErrorCode.EMAIL_DUPLICATE
            );
        }

        /*
         * 새로운 로그인 수단이 등록되면 기존 Refresh Token 세션을 폐기
         *
         * Redis 폐기에 실패하면 예외를 전파하여 DB 트랜잭션도 롤백
         * Redis 폐기 성공 후 DB 커밋이 실패하면 세션만 폐기될 수 있지만,
         * 기존 세션을 남기는 것보다 안전한 fail-closed 결과
         */
        refreshTokenStore.revokeAllByUserId(
            userId
        );
    }

    private void validateArguments(
        UUID userId,
        String normalizedEmail,
        String encodedPassword
    ) {
        if (userId == null) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        if (
            normalizedEmail == null
                || normalizedEmail.isBlank()
        ) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        if (
            encodedPassword == null
                || encodedPassword.isBlank()
        ) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }
    }
}

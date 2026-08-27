package com.mopl.user.service;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.storage.EmailVerificationStore;
import com.mopl.user.storage.RefreshTokenStore;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 회원 탈퇴 전에 기존 Access Token을 차단하고,
 * 데이터베이스 커밋 이후 나머지 인증 상태를 정리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserWithdrawalService {

    private final UserWithdrawalTransactionService transactionService;
    private final RefreshTokenStore refreshTokenStore;
    private final EmailVerificationStore emailVerificationStore;

    /**
     * 기존 Access Token을 먼저 차단한 뒤 사용자를 탈퇴 처리하고
     * 남아 있는 인증 상태를 정리
     *
     * <p>Access Token 차단 상태를 Redis에 기록하지 못하면
     * 탈퇴 처리를 시작하지 않습니다. 차단 이후 DB 처리에 실패한 경우에는
     * 보안을 우선하여 차단 상태를 Access Token 만료 시점까지 유지합니다.</p>
     *
     * <p>DB 탈퇴가 완료된 후 Refresh Token 또는 이메일 인증 상태 정리에
     * 실패해도 이미 완료된 탈퇴를 롤백하지 않습니다.</p>
     */
    public void withdraw(
        UUID authenticatedUserId,
        UUID userId
    ) {
        validateSelf(
            authenticatedUserId,
            userId
        );

        /*
         * 별도 Bean의 @Transactional 메서드가 반환되면
         * 사용자 익명화와 OAuth 연결 삭제가 커밋된 상태
         */
        transactionService.withdraw(
            authenticatedUserId,
            userId
        );

        /*
         * 하나의 Redis 정리가 실패하더라도
         * 나머지 정리는 계속 시도
         */
        revokeRefreshTokens(userId);
        deleteEmailVerification(userId);
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

    private void revokeRefreshTokens(
        UUID userId
    ) {
        try {
            refreshTokenStore.revokeAllByUserId(
                userId
            );
        } catch (RuntimeException exception) {
            log.warn(
                "회원 탈퇴 후 Refresh Token 폐기에 실패했습니다. userId={}",
                userId,
                exception
            );
        }
    }

    private void deleteEmailVerification(
        UUID userId
    ) {
        try {
            emailVerificationStore.deleteByUserId(
                userId
            );
        } catch (RuntimeException exception) {
            log.warn(
                "회원 탈퇴 후 이메일 인증 상태 삭제에 실패했습니다. userId={}",
                userId,
                exception
            );
        }
    }
}

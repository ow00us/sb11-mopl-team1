package com.mopl.user.service;

import com.mopl.user.storage.EmailVerificationStore;
import com.mopl.user.storage.RefreshTokenStore;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 회원 탈퇴 흐름을 조정하고 데이터베이스 커밋 이후
 * 외부 인증 저장소의 사용자 상태를 정리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserWithdrawalService {

    private final UserWithdrawalTransactionService
        transactionService;

    private final RefreshTokenStore refreshTokenStore;

    private final EmailVerificationStore
        emailVerificationStore;

    /**
     * 사용자 탈퇴 처리 후 남아 있는 인증 상태를 정리
     *
     * <p>데이터베이스의 deletedAt이 최종 인증 상태이므로,
     * Redis 정리에 실패해도 이미 완료된 탈퇴를 롤백하거나
     * 실패 응답으로 변경하지 않습니다.</p>
     */
    public void withdraw(
        UUID authenticatedUserId,
        UUID userId
    ) {
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

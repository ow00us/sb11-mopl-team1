package com.mopl.user.service;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.security.JwtProperties;
import com.mopl.user.storage.AccessTokenBlockStore;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 사용자 보안 상태 변경에 맞춰 Access Token 차단 상태를 관리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessTokenBlockLifecycleService {

    private final AccessTokenBlockStore
        accessTokenBlockStore;

    private final JwtProperties jwtProperties;

    /**
     * 기존 Access Token을 최대 유효 시간 동안 차단
     *
     * <p>Redis에 차단 상태를 기록하지 못하면 보안 상태 변경을
     * 시작하지 않도록 503 오류를 발생시킵니다.</p>
     */
    public void block(
        UUID userId
    ) {
        try {
            accessTokenBlockStore.block(
                userId,
                jwtProperties
                    .getAccessTokenExpiration()
            );
        } catch (DataAccessException exception) {
            log.error(
                "Access Token 차단 상태 저장에 실패했습니다. "
                    + "userId={}, cause={}",
                userId,
                exception
                    .getClass()
                    .getSimpleName()
            );

            throw new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE
            );
        }
    }

    /**
     * 현재 DB 트랜잭션이 정상 커밋된 뒤 Access Token 차단을 해제
     *
     * <p>DB 커밋이 실패하면 afterCommit이 호출되지 않으므로
     * 잠긴 사용자의 기존 Access Token이 잘못 허용되지 않습니다.</p>
     */
    public void unblockAfterCommit(
        UUID userId
    ) {
        if (
            !TransactionSynchronizationManager
                .isSynchronizationActive()
        ) {
            unblock(userId);
            return;
        }

        TransactionSynchronizationManager
            .registerSynchronization(
                new TransactionSynchronization() {

                    @Override
                    public void afterCommit() {
                        unblock(userId);
                    }
                }
            );
    }

    /**
     * 차단 해제 실패는 DB 잠금 해제 결과를 되돌릴 수 없으므로
     * 오류를 기록하고 기존 차단 TTL이 만료되도록 유지
     */
    private void unblock(
        UUID userId
    ) {
        try {
            accessTokenBlockStore.unblock(
                userId
            );
        } catch (DataAccessException exception) {
            log.error(
                "Access Token 차단 상태 해제에 실패했습니다. "
                    + "userId={}, cause={}",
                userId,
                exception
                    .getClass()
                    .getSimpleName()
            );
        }
    }
}

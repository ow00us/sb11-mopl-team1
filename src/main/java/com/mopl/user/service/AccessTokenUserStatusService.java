package com.mopl.user.service;

import com.mopl.user.repository.UserRepository;
import com.mopl.user.storage.AccessTokenBlockStore;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Access Token 사용자의 현재 인증 허용 상태를 확인
 *
 * <p>평상시에는 Redis 차단 상태만 조회하고,
 * Redis 장애 시 데이터베이스를 최종 인증 상태로 사용합니다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessTokenUserStatusService {

    private final AccessTokenBlockStore
        accessTokenBlockStore;

    private final UserRepository userRepository;

    /**
     * Access Token 사용자의 인증 허용 상태를 확인
     *
     * @param userId JWT subject에 저장된 사용자 UUID
     * @return 인증 허용, 차단 또는 상태 확인 불가
     */
    public AccessTokenAuthenticationStatus resolve(
        UUID userId
    ) {
        if (userId == null) {
            return AccessTokenAuthenticationStatus.BLOCKED;
        }

        try {
            if (
                accessTokenBlockStore.isBlocked(
                    userId
                )
            ) {
                return AccessTokenAuthenticationStatus.BLOCKED;
            }

            return AccessTokenAuthenticationStatus.ALLOWED;
        } catch (DataAccessException redisException) {
            log.warn(
                "Access Token 차단 상태 Redis 조회에 실패하여 DB로 확인합니다. "
                    + "userId={}, cause={}",
                userId,
                redisException
                    .getClass()
                    .getSimpleName()
            );

            return resolveFromDatabase(
                userId
            );
        }
    }

    private AccessTokenAuthenticationStatus
    resolveFromDatabase(
        UUID userId
    ) {
        try {
            boolean active =
                userRepository
                    .existsByIdAndLockedFalseAndDeletedAtIsNull(
                        userId
                    );

            if (active) {
                return AccessTokenAuthenticationStatus.ALLOWED;
            }

            return AccessTokenAuthenticationStatus.BLOCKED;
        } catch (DataAccessException databaseException) {
            log.error(
                "Access Token 사용자 상태를 Redis와 DB에서 모두 확인하지 못했습니다. "
                    + "userId={}, cause={}",
                userId,
                databaseException
                    .getClass()
                    .getSimpleName()
            );

            return AccessTokenAuthenticationStatus.UNAVAILABLE;
        }
    }
}

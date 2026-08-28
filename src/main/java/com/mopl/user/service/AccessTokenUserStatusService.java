package com.mopl.user.service;

import com.mopl.user.repository.UserRepository;
import com.mopl.user.storage.AccessTokenBlockStore;
import com.mopl.global.security.JwtProperties;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Access Token 사용자의 현재 인증 허용 상태를 확인
 *
 * <p>Redis에 상태가 있으면 이를 사용하고, 캐시 미스 또는 Redis 장애 시
 * 데이터베이스를 최종 인증 상태로 사용합니다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessTokenUserStatusService {

    private final AccessTokenBlockStore accessTokenBlockStore;
    private final UserRepository userRepository;
    private final JwtProperties jwtProperties;

    public AccessTokenAuthenticationStatus resolve(
        UUID userId
    ) {
        if (userId == null) {
            return AccessTokenAuthenticationStatus.BLOCKED;
        }

        Optional<Boolean> cachedBlocked;

        try {
            cachedBlocked =
                accessTokenBlockStore.findBlocked(
                    userId
                );
        } catch (DataAccessException redisException) {
            log.warn(
                "Access Token 상태 Redis 조회에 실패하여 DB로 확인합니다. "
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

        if (cachedBlocked.isPresent()) {
            return cachedBlocked.get()
                ? AccessTokenAuthenticationStatus.BLOCKED
                : AccessTokenAuthenticationStatus.ALLOWED;
        }

        AccessTokenAuthenticationStatus databaseStatus =
            resolveFromDatabase(
                userId
            );

        if (
            databaseStatus
                == AccessTokenAuthenticationStatus.ALLOWED
        ) {
            cacheAllowed(
                userId
            );
        }

        return databaseStatus;
    }

    private void cacheAllowed(
        UUID userId
    ) {
        try {
            accessTokenBlockStore.allowIfAbsent(
                userId,
                jwtProperties
                    .getAccessTokenExpiration()
            );
        } catch (DataAccessException redisException) {
            /*
             * DB에서 사용자 활성 상태를 확인했으므로 현재 요청은 허용
             * 캐시 저장 실패는 다음 요청에서 다시 DB를 조회하도록 남긴다.
             */
            log.warn(
                "Access Token 허용 상태 Redis 저장에 실패했습니다. "
                    + "userId={}, cause={}",
                userId,
                redisException
                    .getClass()
                    .getSimpleName()
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

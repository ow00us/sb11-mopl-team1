package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mopl.global.exception.ErrorCode;
import com.mopl.global.security.JwtProperties;
import com.mopl.user.storage.AccessTokenBlockStore;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class AccessTokenBlockLifecycleServiceTest {

    private static final Duration ACCESS_TOKEN_EXPIRATION =
        Duration.ofHours(3);

    @Mock
    AccessTokenBlockStore accessTokenBlockStore;

    AccessTokenBlockLifecycleService service;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setAccessTokenExpiration(
            ACCESS_TOKEN_EXPIRATION
        );

        service = new AccessTokenBlockLifecycleService(
            accessTokenBlockStore,
            jwtProperties
        );
    }

    @Test
    void block_storesBlockedStateForAccessTokenExpiration() {
        UUID userId = UUID.randomUUID();

        service.block(userId);

        verify(accessTokenBlockStore).block(
            userId,
            ACCESS_TOKEN_EXPIRATION
        );
    }

    @Test
    void block_failsClosed_whenRedisWriteFails() {
        UUID userId = UUID.randomUUID();

        doThrow(
            new DataAccessResourceFailureException(
                "Redis 연결 실패"
            )
        ).when(accessTokenBlockStore)
            .block(
                userId,
                ACCESS_TOKEN_EXPIRATION
            );

        assertThatThrownBy(() ->
            service.block(userId)
        )
            .extracting("errorCode")
            .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void unblockAfterCommit_unblocksImmediately_withoutTransactionSynchronization() {
        UUID userId = UUID.randomUUID();

        service.unblockAfterCommit(userId);

        verify(accessTokenBlockStore)
            .unblock(userId);
    }

    @Test
    void unblockAfterCommit_waitsUntilTransactionCommit() {
        UUID userId = UUID.randomUUID();

        TransactionSynchronizationManager
            .initSynchronization();

        try {
            service.unblockAfterCommit(userId);

            verifyNoInteractions(
                accessTokenBlockStore
            );

            TransactionSynchronization synchronization =
                TransactionSynchronizationManager
                    .getSynchronizations()
                    .get(0);

            synchronization.afterCommit();

            verify(accessTokenBlockStore)
                .unblock(userId);
        } finally {
            TransactionSynchronizationManager
                .clearSynchronization();
        }
    }

    @Test
    void unblockAfterCommit_keepsBlockedState_whenRedisDeleteFails() {
        UUID userId = UUID.randomUUID();

        doThrow(
            new DataAccessResourceFailureException(
                "Redis 연결 실패"
            )
        ).when(accessTokenBlockStore)
            .unblock(userId);

        assertThatCode(() ->
            service.unblockAfterCommit(userId)
        ).doesNotThrowAnyException();

        verify(accessTokenBlockStore)
            .unblock(userId);
    }
}

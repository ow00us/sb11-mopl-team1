package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.mopl.global.config.JpaConfig;
import com.mopl.user.entity.OAuthAccount;
import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.OAuthAccountRepository;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.storage.RefreshTokenStore;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * OAuth 계정 관리 서비스의 실제 데이터베이스 트랜잭션 동작을 검증
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({
    JpaConfig.class,
    OAuthAccountManagementService.class
})
@AutoConfigureTestDatabase(
    replace = AutoConfigureTestDatabase.Replace.NONE
)
@Testcontainers
@Transactional(
    propagation = Propagation.NOT_SUPPORTED
)
class OAuthAccountManagementServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16");

    @Autowired
    OAuthAccountManagementService managementService;

    @Autowired
    OAuthAccountRepository oauthAccountRepository;

    @Autowired
    UserRepository userRepository;

    @MockitoBean
    RefreshTokenStore refreshTokenStore;

    @BeforeEach
    void setUp() {
        oauthAccountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("세션 폐기에 실패하면 OAuth 계정 연결 삭제를 롤백한다")
    void unlinkAccount_rollsBack_whenRefreshTokenRevocationFails() {
        // given
        User user =
            User.builder()
                .email("local-user@example.com")
                .passwordHash("encoded-password")
                .name("로컬 사용자")
                .profileImageUrl(null)
                .role(UserRole.USER)
                .locked(false)
                .build();

        userRepository.saveAndFlush(user);

        UUID userId = user.getId();

        OAuthAccount oauthAccount =
            OAuthAccount.builder()
                .user(user)
                .provider(OAuthProvider.GOOGLE)
                .providerUserId("google-sub-123")
                .build();

        oauthAccountRepository.saveAndFlush(
            oauthAccount
        );

        IllegalStateException redisException =
            new IllegalStateException(
                "Redis 세션 폐기 실패"
            );

        when(
            refreshTokenStore
                .revokeAllByUserId(userId)
        ).thenThrow(
            redisException
        );

        // when & then
        assertThatThrownBy(() ->
            managementService.unlinkAccount(
                userId,
                userId,
                OAuthProvider.GOOGLE
            )
        ).isSameAs(
            redisException
        );

        /*
         * 서비스 트랜잭션 종료 후 DB를 다시 조회
         * Redis 호출 전에 실행된 DELETE가 실제로 롤백됐는지 검증
         */
        assertThat(
            oauthAccountRepository
                .findByUserIdAndProvider(
                    userId,
                    OAuthProvider.GOOGLE
                )
        ).isPresent();

        assertThat(
            oauthAccountRepository
                .countByUserId(userId)
        ).isEqualTo(1);
    }
}

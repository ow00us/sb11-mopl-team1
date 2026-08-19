package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.global.config.JpaConfig;
import com.mopl.user.dto.ChangePasswordRequest;
import com.mopl.user.dto.UserLockUpdateRequest;
import com.mopl.user.dto.UserRoleUpdateRequest;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.storage.ProfileImageStorage;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 사용자 보안 상태 변경과 Refresh Token 세션 폐기의
 * 트랜잭션 경계를 검증하는 통합 테스트
 *
 * <p>UserService 단위 테스트는 RefreshTokenStore 호출과 예외 전달을
 * 검증하지만, Redis 폐기 실패 시 JPA 변경 감지가 실제로 롤백되는지는
 * 확인할 수 없습니다.</p>
 *
 * <p>이 테스트는 PostgreSQL Testcontainers와 실제 Spring 트랜잭션을
 * 사용하여 다음 값이 Redis 실패 이후에도 유지되는지 확인합니다.</p>
 *
 * <ul>
 *     <li>비밀번호 해시</li>
 *     <li>사용자 권한</li>
 *     <li>계정 잠금 상태</li>
 * </ul>
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({
    JpaConfig.class,
    UserService.class
})
@AutoConfigureTestDatabase(
    replace =
        AutoConfigureTestDatabase
            .Replace
            .NONE
)
@Testcontainers
@Transactional(
    propagation = Propagation.NOT_SUPPORTED
)
class UserSecurityStateIntegrationTest {

    /**
     * 운영 데이터베이스와 동일한 PostgreSQL 기반으로
     * 실제 트랜잭션 커밋과 롤백을 검증
     */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(
            "postgres:16"
        );

    /**
     * 테스트 사용자를 저장하고 트랜잭션 종료 후 다시 조회
     */
    @Autowired
    UserRepository userRepository;

    /**
     * 이번 테스트에서 검증할 실제 UserService Bean
     */
    @Autowired
    UserService userService;

    /**
     * 비밀번호 인코딩 결과를 고정하여 DB 값을 예측 가능하게 만든다.
     */
    @MockitoBean
    PasswordEncoder passwordEncoder;

    /**
     * UserService 생성자 의존성을 만족시키기 위한 Mock Bean
     *
     * <p>이번 테스트에서는 프로필 수정 기능을 호출하지 않습니다.</p>
     */
    @MockitoBean
    ProfileImageStorage profileImageStorage;

    /**
     * 사용자별 Refresh Token 전체 폐기의 성공과 실패를 제어
     */
    @MockitoBean
    RefreshTokenStore refreshTokenStore;

    /**
     * 각 테스트 사이에 사용자 데이터가 섞이지 않도록 초기화
     */
    @BeforeEach
    void cleanUpUsers() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("비밀번호 변경 중 세션 폐기에 실패하면 기존 비밀번호 해시를 유지한다")
    void changePassword_rollsBack_whenRevocationFails() {
        // given
        String oldPasswordHash =
            "old-encoded-password";

        String newPasswordHash =
            "new-encoded-password";

        UUID userId =
            saveUser(
                oldPasswordHash,
                UserRole.USER,
                false
            );

        when(
            passwordEncoder.encode(
                "NewPassword1!"
            )
        ).thenReturn(
            newPasswordHash
        );

        IllegalStateException redisException =
            redisFailure();

        when(
            refreshTokenStore
                .revokeAllByUserId(userId)
        ).thenThrow(redisException);

        // when & then
        assertThatThrownBy(() ->
            userService.changePassword(
                userId,
                userId,
                new ChangePasswordRequest(
                    "NewPassword1!"
                )
            )
        ).isSameAs(redisException);

        /*
         * 서비스 트랜잭션이 종료된 다음 PostgreSQL에서 다시 조회
         * 메모리 엔티티가 아니라 실제 DB 값을 확인해야 한다.
         */
        User unchangedUser =
            userRepository
                .findById(userId)
                .orElseThrow();

        assertThat(
            unchangedUser.getPasswordHash()
        ).isEqualTo(
            oldPasswordHash
        );

        assertThat(
            unchangedUser.getPasswordHash()
        ).isNotEqualTo(
            newPasswordHash
        );

        verify(refreshTokenStore)
            .revokeAllByUserId(userId);
    }

    @Test
    @DisplayName("권한 변경 중 세션 폐기에 실패하면 기존 권한을 유지한다")
    void updateRole_rollsBack_whenRevocationFails() {
        // given
        UUID userId =
            saveUser(
                "encoded-password",
                UserRole.USER,
                false
            );

        IllegalStateException redisException =
            redisFailure();

        when(
            refreshTokenStore
                .revokeAllByUserId(userId)
        ).thenThrow(redisException);

        // when & then
        assertThatThrownBy(() ->
            userService.updateRole(
                userId,
                new UserRoleUpdateRequest(
                    UserRole.ADMIN
                )
            )
        ).isSameAs(redisException);

        User unchangedUser =
            userRepository
                .findById(userId)
                .orElseThrow();

        /*
         * Redis 폐기를 보장할 수 없으므로 ADMIN 권한 변경도
         * 데이터베이스에 커밋되면 안된다.
         */
        assertThat(
            unchangedUser.getRole()
        ).isEqualTo(
            UserRole.USER
        );

        verify(refreshTokenStore)
            .revokeAllByUserId(userId);
    }

    @Test
    @DisplayName("계정 잠금 중 세션 폐기에 실패하면 기존 잠금 상태를 유지한다")
    void updateLocked_rollsBack_whenRevocationFails() {
        // given
        UUID userId =
            saveUser(
                "encoded-password",
                UserRole.USER,
                false
            );

        IllegalStateException redisException =
            redisFailure();

        when(
            refreshTokenStore
                .revokeAllByUserId(userId)
        ).thenThrow(redisException);

        // when & then
        assertThatThrownBy(() ->
            userService.updateLocked(
                userId,
                new UserLockUpdateRequest(true)
            )
        ).isSameAs(redisException);

        User unchangedUser =
            userRepository
                .findById(userId)
                .orElseThrow();

        /*
         * 계정 잠금만 반영되고 기존 Refresh Token 세션이 남는
         * 불일치 상태가 발생하면 안된다.
         */
        assertThat(
            unchangedUser.isLocked()
        ).isFalse();

        verify(refreshTokenStore)
            .revokeAllByUserId(userId);
    }

    /**
     * PostgreSQL에 테스트 사용자를 저장하고 생성된 UUID를 반환
     *
     * @param passwordHash 초기 비밀번호 해시
     * @param role 초기 사용자 권한
     * @param locked 초기 계정 잠금 상태
     * @return JPA가 생성한 사용자 UUID
     */
    private UUID saveUser(
        String passwordHash,
        UserRole role,
        boolean locked
    ) {
        User user =
            User.builder()
                .email("user@example.com")
                .passwordHash(passwordHash)
                .name("테스트 사용자")
                .role(role)
                .locked(locked)
                .build();

        User savedUser =
            userRepository.saveAndFlush(user);

        return savedUser.getId();
    }

    /**
     * Redis 세션 폐기 실패를 나타내는 동일한 예외를 생성
     */
    private IllegalStateException redisFailure() {
        return new IllegalStateException(
            "Redis 세션 폐기 실패"
        );
    }
}

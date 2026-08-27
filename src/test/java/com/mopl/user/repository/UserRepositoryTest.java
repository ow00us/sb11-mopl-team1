package com.mopl.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.global.config.JpaConfig;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link UserRepository}의 데이터베이스 동작을 검증하는 테스트입니다.
 *
 * <p>단순한 메서드 호출만 확인하는 단위 테스트가 아니라,
 * Testcontainers가 실행한 실제 PostgreSQL에 사용자를 저장하고 조회합니다.</p>
 *
 * <p>이를 통해 다음 요소가 함께 올바르게 연결됐는지 확인할 수 있습니다.</p>
 *
 * <ul>
 *     <li>{@link User} 엔티티와 {@code users} 테이블의 매핑</li>
 *     <li>Flyway로 생성한 실제 PostgreSQL 스키마</li>
 *     <li>{@link UserRepository}의 이메일 조회 쿼리</li>
 *     <li>{@code BaseEntity}의 UUID 및 생성·수정 시각 처리</li>
 * </ul>
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(JpaConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserRepositoryTest {

    /**
     * Repository 테스트에서 사용할 PostgreSQL 컨테이너입니다.
     *
     * <p>{@code @Container}가 테스트 실행 전 PostgreSQL 컨테이너를 시작하고,
     * 테스트 종료 후 컨테이너를 정리합니다.</p>
     *
     * <p>{@code @ServiceConnection}은 컨테이너의 JDBC 주소, 사용자 이름,
     * 비밀번호를 Spring Boot 데이터소스에 자동으로 연결합니다.</p>
     */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16");

    /**
     * JPA 엔티티를 직접 저장하고 영속성 컨텍스트를 제어하기 위한 테스트 도구입니다.
     *
     * <p>테스트 대상은 {@link UserRepository}의 조회 기능이므로,
     * 테스트 데이터를 준비할 때는 Repository가 아닌 TestEntityManager를 사용합니다.
     * 이렇게 하면 데이터 준비 과정과 실제 검증 대상을 구분할 수 있습니다.</p>
     */
    @Autowired
    TestEntityManager entityManager;

    /**
     * 이번 테스트에서 검증할 사용자 Repository입니다.
     */
    @Autowired
    UserRepository userRepository;

    /**
     * 사용자를 저장한 후 정규화된 이메일로 다시 조회할 수 있는지 검증합니다.
     *
     * <p>회원가입 서비스는 이메일을 소문자로 정규화한 뒤 저장할 예정입니다.
     * Repository는 전달받은 정규화된 이메일을 기준으로 사용자를 조회합니다.</p>
     */
    @Test
    @DisplayName("저장한 사용자를 이메일로 조회할 수 있다")
    void findByEmail_success() {
        // given: PostgreSQL에 저장할 사용자 엔티티를 생성합니다.
        //
        // passwordHash에는 실제 비밀번호 원문이 아니라
        // PasswordEncoder로 인코딩됐다고 가정한 문자열을 사용합니다.
        User user = User.builder()
            .email("user@example.com")
            .passwordHash("encoded-password")
            .name("테스트 사용자")
            .role(UserRole.USER)
            .locked(false)
            .build();

        // persistAndFlush는 엔티티를 영속성 컨텍스트에 등록하고,
        // INSERT SQL을 즉시 PostgreSQL로 전달합니다.
        entityManager.persistAndFlush(user);

        // 영속성 컨텍스트를 초기화합니다.
        //
        // 이 작업이 없으면 JPA가 메모리에 보관 중인 User 객체를 그대로 돌려줄 수 있습니다.
        // clear() 이후 조회하면 Repository가 실제로 DB에 SELECT 쿼리를 실행하게 됩니다.
        entityManager.clear();

        // when: 회원가입 시 저장한 정규화 이메일로 사용자를 조회합니다.
        Optional<User> result = userRepository.findByEmail("user@example.com");

        // then: 사용자가 존재하고 주요 필드가 올바르게 저장됐는지 확인합니다.
        assertThat(result).isPresent();

        User foundUser = result.get();

        assertThat(foundUser.getEmail()).isEqualTo("user@example.com");
        assertThat(foundUser.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(foundUser.getName()).isEqualTo("테스트 사용자");
        assertThat(foundUser.getRole()).isEqualTo(UserRole.USER);
        assertThat(foundUser.isLocked()).isFalse();

        // id, createdAt, updatedAt은 User가 아닌 BaseEntity에서 제공합니다.
        // UUID 생성과 JPA Auditing이 정상적으로 동작했는지 함께 확인합니다.
        assertThat(foundUser.getId()).isNotNull();
        assertThat(foundUser.getCreatedAt()).isNotNull();
        assertThat(foundUser.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("탈퇴 사용자는 활성 사용자 조회에서 제외하지만 일반 UUID 조회는 유지한다")
    void activeUserQueries_excludeDeletedUser() {
        // given
        User user = User.builder()
            .email("withdraw@example.com")
            .passwordHash("encoded-password")
            .name("탈퇴 전 사용자")
            .role(UserRole.USER)
            .locked(false)
            .build();

        entityManager.persistAndFlush(user);

        UUID userId = user.getId();

        user.withdraw(
            Instant.parse("2026-08-27T00:00:00Z")
        );

        entityManager.flush();
        entityManager.clear();

        String anonymizedEmail =
            "deleted-" + userId + "@deleted.mopl";

        // when & then
        assertThat(
            userRepository.findById(userId)
        ).isPresent();

        assertThat(
            userRepository.findByIdAndDeletedAtIsNull(userId)
        ).isEmpty();

        assertThat(
            userRepository.existsByIdAndDeletedAtIsNull(userId)
        ).isFalse();

        assertThat(
            userRepository.findByEmailAndDeletedAtIsNull(
                anonymizedEmail
            )
        ).isEmpty();

        assertThat(
            userRepository.findByIdForUpdate(userId)
        ).isEmpty();
    }

    @Test
    @DisplayName("Access Token 인증은 활성 상태이며 잠기지 않은 사용자에게만 허용한다")
    void accessTokenAuthenticationQuery_allowsOnlyActiveUnlockedUser() {
        // given
        User activeUser = User.builder()
            .email("active-auth@example.com")
            .passwordHash("encoded-password")
            .name("활성 사용자")
            .role(UserRole.USER)
            .locked(false)
            .build();

        User lockedUser = User.builder()
            .email("locked-auth@example.com")
            .passwordHash("encoded-password")
            .name("잠긴 사용자")
            .role(UserRole.USER)
            .locked(true)
            .build();

        User deletedUser = User.builder()
            .email("deleted-auth@example.com")
            .passwordHash("encoded-password")
            .name("탈퇴 예정 사용자")
            .role(UserRole.USER)
            .locked(false)
            .build();

        entityManager.persist(activeUser);
        entityManager.persist(lockedUser);
        entityManager.persist(deletedUser);
        entityManager.flush();

        UUID activeUserId = activeUser.getId();
        UUID lockedUserId = lockedUser.getId();
        UUID deletedUserId = deletedUser.getId();

        deletedUser.withdraw(
            Instant.parse(
                "2026-08-27T00:00:00Z"
            )
        );

        entityManager.flush();
        entityManager.clear();

        // when & then
        assertThat(
            userRepository
                .existsByIdAndLockedFalseAndDeletedAtIsNull(
                    activeUserId
                )
        ).isTrue();

        assertThat(
            userRepository
                .existsByIdAndLockedFalseAndDeletedAtIsNull(
                    lockedUserId
                )
        ).isFalse();

        assertThat(
            userRepository
                .existsByIdAndLockedFalseAndDeletedAtIsNull(
                    deletedUserId
                )
        ).isFalse();

        assertThat(
            userRepository
                .existsByIdAndLockedFalseAndDeletedAtIsNull(
                    UUID.randomUUID()
                )
        ).isFalse();
    }
}

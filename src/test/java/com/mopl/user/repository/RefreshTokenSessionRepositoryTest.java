package com.mopl.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.global.config.JpaConfig;
import com.mopl.user.entity.RefreshTokenSession;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link RefreshTokenSessionRepository}와 PostgreSQL 스키마의 연결을 검증하는 통합 테스트
 *
 * Mockito로 Repository 호출 여부만 확인하는 테스트가 아니라,
 * Testcontainers로 실행한 실제 PostgreSQL에 데이터를 저장하고 조회
 *
 * 이를 통해 RefreshTokenSession 엔티티 매핑, Flyway 마이그레이션,
 * 외래 키, 유일성 제약과 Repository 조회 메서드를 함께 검증
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(JpaConfig.class)
@AutoConfigureTestDatabase(
    replace = AutoConfigureTestDatabase.Replace.NONE
)
@Testcontainers
class RefreshTokenSessionRepositoryTest {

    /**
     * Repository 테스트에서 사용할 PostgreSQL 16 컨테이너
     *
     * @ServiceConnection이 컨테이너의 JDBC URL, 사용자 이름과 비밀번호를
     * 테스트 ApplicationContext의 DataSource 설정으로 자동 연결
     */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16");

    /**
     * 테스트용 사용자 준비와 영속성 컨텍스트 초기화에 사용
     */
    @Autowired
    TestEntityManager entityManager;

    /**
     * 이번 테스트의 대상 Repository
     */
    @Autowired
    RefreshTokenSessionRepository refreshTokenSessionRepository;

    /**
     * Refresh Token 세션을 저장한 뒤 토큰 해시로 다시 조회할 수 있는지 검증
     *
     * DB에는 Refresh Token 원문이 아니라 64자의 SHA-256 해시가 저장된다는
     * 저장 계약도 함께 확인
     */
    @Test
    @DisplayName("Refresh Token 세션을 저장하고 토큰 해시로 조회한다")
    void saveAndFindByTokenHash_success() {
        // given: 외래 키 제약을 만족하도록 Refresh Token 소유 사용자를 먼저 저장
        User user = persistUser("refresh-owner@example.com");

        /*
         * PostgreSQL의 TIMESTAMP(6)는 마이크로초까지만 저장
         * Java Instant가 가진 나노초 정밀도와 DB의 정밀도 차이로
         * 테스트가 불안정해지지 않도록 마이크로초 단위로 절삭
         */
        Instant expiresAt = Instant.now()
            .plus(Duration.ofDays(7))
            .truncatedTo(ChronoUnit.MICROS);

        /*
         * 실제 토큰 해시 생성 기능은 다음 단계에서 구현
         * Repository 테스트에서는 SHA-256 16진수 결과와 같은 길이인
         * 64자의 테스트 값을 사용
         */
        String tokenHash = "a".repeat(64);

        RefreshTokenSession session =
            RefreshTokenSession.builder()
                .userId(user.getId())
                .tokenHash(tokenHash)
                .expiresAt(expiresAt)
                .build();

        // INSERT SQL을 즉시 PostgreSQL에 전달
        refreshTokenSessionRepository.saveAndFlush(session);

        UUID sessionId = session.getId();

        /*
         * 영속성 컨텍스트를 비워서 메모리에 남아 있는 엔티티가 아니라
         * Repository가 실제 SELECT 쿼리로 DB에서 조회하도록 한다.
         */
        entityManager.clear();

        // when
        Optional<RefreshTokenSession> result =
            refreshTokenSessionRepository.findByTokenHash(tokenHash);

        // then
        assertThat(result).isPresent();

        RefreshTokenSession savedSession = result.get();

        assertThat(savedSession.getId()).isEqualTo(sessionId);
        assertThat(savedSession.getUserId()).isEqualTo(user.getId());
        assertThat(savedSession.getTokenHash()).isEqualTo(tokenHash);
        assertThat(savedSession.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(savedSession.getRevokedAt()).isNull();

        /*
         * id와 생성·수정 시각은 RefreshTokenSession이 직접 선언하지 않고
         * BaseEntity와 JPA Auditing을 통해 자동으로 생성
         */
        assertThat(savedSession.getCreatedAt()).isNotNull();
        assertThat(savedSession.getUpdatedAt()).isNotNull();
    }

    /**
     * 동일한 사용자에게 여러 Refresh Token 세션을 저장할 수 있는지 검증
     *
     * 이는 한 사용자가 여러 브라우저 또는 기기에서 동시에 로그인할 수 있다는
     * 다중 세션 정책을 데이터베이스 구조가 지원하는지 확인하는 테스트
     */
    @Test
    @DisplayName("한 사용자에게 여러 Refresh Token 세션을 저장할 수 있다")
    void findAllByUserId_successWhenUserHasMultipleSessions() {
        // given
        User user = persistUser("multi-session@example.com");
        User otherUser = persistUser("other-session@example.com");

        Instant expiresAt = Instant.now()
            .plus(Duration.ofDays(7))
            .truncatedTo(ChronoUnit.MICROS);

        RefreshTokenSession chromeSession =
            RefreshTokenSession.builder()
                .userId(user.getId())
                .tokenHash("b".repeat(64))
                .expiresAt(expiresAt)
                .build();

        RefreshTokenSession mobileSession =
            RefreshTokenSession.builder()
                .userId(user.getId())
                .tokenHash("c".repeat(64))
                .expiresAt(expiresAt)
                .build();

        RefreshTokenSession otherUserSession =
            RefreshTokenSession.builder()
                .userId(otherUser.getId())
                .tokenHash("e".repeat(64))
                .expiresAt(expiresAt)
                .build();

        refreshTokenSessionRepository.saveAllAndFlush(
            List.of(
                chromeSession,
                mobileSession,
                otherUserSession
            )
        );

        entityManager.clear();

        // when
        List<RefreshTokenSession> result =
            refreshTokenSessionRepository.findAllByUserId(user.getId());

        // then
        assertThat(result).hasSize(2);

        assertThat(result)
            .extracting(RefreshTokenSession::getTokenHash)
            .containsExactlyInAnyOrder(
                "b".repeat(64),
                "c".repeat(64)
            );

        assertThat(result)
            .extracting(RefreshTokenSession::getUserId)
            .containsOnly(user.getId());
    }

    /**
     * 동일한 토큰 해시가 중복 저장되지 않는지 검증
     *
     * 애플리케이션 코드에 문제가 있어 같은 토큰을 두 번 저장하려 하더라도
     * 데이터베이스의 유일성 제약이 마지막 방어선으로 동작해야 한다.
     */
    @Test
    @DisplayName("동일한 Refresh Token 해시는 중복 저장할 수 없다")
    void save_failWhenTokenHashIsDuplicated() {
        // given
        User user = persistUser("duplicate-token@example.com");

        Instant expiresAt = Instant.now()
            .plus(Duration.ofDays(7))
            .truncatedTo(ChronoUnit.MICROS);

        String duplicatedTokenHash = "d".repeat(64);

        RefreshTokenSession firstSession =
            RefreshTokenSession.builder()
                .userId(user.getId())
                .tokenHash(duplicatedTokenHash)
                .expiresAt(expiresAt)
                .build();

        RefreshTokenSession secondSession =
            RefreshTokenSession.builder()
                .userId(user.getId())
                .tokenHash(duplicatedTokenHash)
                .expiresAt(expiresAt)
                .build();

        refreshTokenSessionRepository.saveAndFlush(firstSession);

        /*
         * when & then
         *
         * save()만 호출하면 INSERT가 트랜잭션 종료 시점까지 지연될 수 있다.
         * saveAndFlush()를 사용해 테스트 구문 안에서 즉시 SQL을 실행하고
         * 유일성 제약 위반을 확인
         */
        assertThatThrownBy(() ->
            refreshTokenSessionRepository.saveAndFlush(secondSession)
        )
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Refresh Token 세션 테스트에서 사용할 사용자를 저장
     *
     * refresh_token_sessions.user_id에는 users.id 외래 키가 있으므로
     * Refresh Token 세션을 저장하기 전에 실제 사용자가 존재해야 한다.
     *
     * @param email 테스트 사용자에게 사용할 고유 이메일
     * @return PostgreSQL에 저장된 사용자 엔티티
     */
    private User persistUser(String email) {
        User user = User.builder()
            .email(email)
            .passwordHash("encoded-password")
            .name("Refresh Token 테스트 사용자")
            .role(UserRole.USER)
            .locked(false)
            .build();

        entityManager.persistAndFlush(user);

        return user;
    }
}

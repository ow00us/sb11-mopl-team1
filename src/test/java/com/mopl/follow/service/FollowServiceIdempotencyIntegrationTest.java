package com.mopl.follow.service;

import com.mopl.follow.entity.Follow;
import com.mopl.follow.repository.FollowRepository;
import com.mopl.global.config.JpaConfig;
import com.mopl.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 PostgreSQL 에서 팔로우 멱등 계약(ADR 2)이 지켜지는지 검증한다.
 * <p>CodeRabbit 리뷰: 유니크 제약 위반 시 트랜잭션이 abort 되므로 catch 블록 안에서
 * 재조회하지 말고 {@code ON CONFLICT DO NOTHING} 로 처리해야 함.
 * 이 테스트는 순차/repeat 호출 모두 서비스가 트랜잭션 오염 없이 정상 200 을 돌려주는지 확인한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, FollowService.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FollowServiceIdempotencyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired FollowService followService;
    @Autowired FollowRepository followRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @SuppressWarnings("unused") @Autowired UserRepository userRepository;

    private static final UUID FOLLOWER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID FOLLOWEE_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM follows");
        jdbcTemplate.update("DELETE FROM users");

        Instant now = Instant.now();
        for (UUID uid : new UUID[]{FOLLOWER_ID, FOLLOWEE_ID}) {
            jdbcTemplate.update(
                    "INSERT INTO users (id, created_at, updated_at, email, password_hash, name, role) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    uid, Timestamp.from(now), Timestamp.from(now),
                    uid + "@test.com", "hash", "user-" + uid, "USER"
            );
        }
    }

    @Test
    @DisplayName("첫 요청은 created=true, 재요청은 created=false 로 200 을 반환하며 rows 는 1건만 존재한다")
    void follow_idempotent_returnsExistingOnDuplicate() {
        FollowResult first = followService.follow(FOLLOWER_ID, FOLLOWEE_ID);
        FollowResult second = followService.follow(FOLLOWER_ID, FOLLOWEE_ID);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.dto().id()).isEqualTo(first.dto().id());

        List<Follow> rows = followRepository.findAll();
        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("같은 관계로 3번 연속 요청해도 예외 없이 마지막 응답이 200 이고 rows 는 여전히 1건이다")
    void follow_repeatedCalls_neverPoisonTransaction() {
        FollowResult first = followService.follow(FOLLOWER_ID, FOLLOWEE_ID);
        for (int i = 0; i < 3; i++) {
            FollowResult r = followService.follow(FOLLOWER_ID, FOLLOWEE_ID);
            assertThat(r.created()).isFalse();
            assertThat(r.dto().id()).isEqualTo(first.dto().id());
        }
        assertThat(followRepository.findAll()).hasSize(1);
    }
}
package com.mopl.follow.repository;

import com.mopl.follow.entity.Follow;
import com.mopl.global.config.JpaConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class FollowRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired TestEntityManager em;
    @Autowired FollowRepository followRepository;

    private static final UUID USER_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID USER_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID USER_C = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID USER_D = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @BeforeEach
    void insertTestUsers() {
        em.getEntityManager().createNativeQuery("""
                INSERT INTO users (id, created_at, updated_at, email, name, password_hash, role, locked)
                VALUES
                  (:a, NOW(), NOW(), 'a@test.com', 'A', 'hash', 'USER', false),
                  (:b, NOW(), NOW(), 'b@test.com', 'B', 'hash', 'USER', false),
                  (:c, NOW(), NOW(), 'c@test.com', 'C', 'hash', 'USER', false),
                  (:d, NOW(), NOW(), 'd@test.com', 'D', 'hash', 'USER', false)
                ON CONFLICT DO NOTHING
                """)
                .setParameter("a", USER_A)
                .setParameter("b", USER_B)
                .setParameter("c", USER_C)
                .setParameter("d", USER_D)
                .executeUpdate();
        em.flush();
    }

    // ── countByFollowerId ─────────────────────────────────────────────────────

    @Test
    @DisplayName("countByFollowerId 는 특정 followerId 가 팔로우한 관계 수를 반환한다")
    void countByFollowerId_success() {
        persistFollow(USER_A, USER_B, Instant.now());
        persistFollow(USER_A, USER_C, Instant.now());
        persistFollow(USER_D, USER_B, Instant.now());  // 다른 follower 관계는 카운트 미포함

        assertThat(followRepository.countByFollowerId(USER_A)).isEqualTo(2L);
        assertThat(followRepository.countByFollowerId(USER_D)).isEqualTo(1L);
        assertThat(followRepository.countByFollowerId(USER_B)).isEqualTo(0L);
    }

    // ── findFollowersByFolloweeIdDesc ─────────────────────────────────────────

    @Test
    @DisplayName("findFollowersByFolloweeIdDesc 는 followee 의 팔로워를 createdAt DESC 로 반환한다")
    void findFollowersByFolloweeIdDesc_orderDesc() {
        Instant t1 = Instant.parse("2026-08-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-08-01T11:00:00Z");
        Instant t3 = Instant.parse("2026-08-01T12:00:00Z");

        persistFollow(USER_A, USER_B, t1);
        persistFollow(USER_C, USER_B, t3);  // 가장 최근
        persistFollow(USER_D, USER_B, t2);
        persistFollow(USER_A, USER_C, t3);  // followee 가 USER_C — 결과에 없어야 함

        List<Follow> result = followRepository.findFollowersByFolloweeIdDesc(
                USER_B.toString(), null, null, 10);

        assertThat(result).extracting(Follow::getFollowerId)
                .containsExactly(USER_C, USER_D, USER_A);  // 최근순
    }

    @Test
    @DisplayName("findFollowersByFolloweeIdDesc 는 cursor+idAfter 로 다음 페이지를 반환한다")
    void findFollowersByFolloweeIdDesc_cursorPagination() {
        Instant base = Instant.parse("2026-08-01T10:00:00Z");
        Follow f1 = persistFollow(USER_A, USER_B, base);
        Follow f2 = persistFollow(USER_C, USER_B, base);           // 같은 시각 → id 타이브레이커
        Follow f3 = persistFollow(USER_D, USER_B, base.plusSeconds(60));

        // 1페이지: 최근 1건
        List<Follow> page1 = followRepository.findFollowersByFolloweeIdDesc(
                USER_B.toString(), null, null, 1);
        assertThat(page1).extracting(Follow::getId).containsExactly(f3.getId());

        // 2페이지: page1 의 마지막을 커서로 → 같은 시각의 두 건 중 id ASC 첫 번째
        Follow last1 = page1.get(0);
        List<Follow> page2 = followRepository.findFollowersByFolloweeIdDesc(
                USER_B.toString(), last1.getCreatedAt(), last1.getId().toString(), 10);

        // 같은 createdAt 인 f1, f2 는 id ASC 로 정렬됨
        UUID smaller = f1.getId().compareTo(f2.getId()) < 0 ? f1.getId() : f2.getId();
        UUID larger  = f1.getId().compareTo(f2.getId()) < 0 ? f2.getId() : f1.getId();
        assertThat(page2).extracting(Follow::getId).containsExactly(smaller, larger);
    }

    // ── findFollowingsByFollowerIdDesc ────────────────────────────────────────

    @Test
    @DisplayName("findFollowingsByFollowerIdDesc 는 follower 가 팔로우 중인 관계를 createdAt DESC 로 반환한다")
    void findFollowingsByFollowerIdDesc_orderDesc() {
        Instant t1 = Instant.parse("2026-08-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-08-01T11:00:00Z");
        Instant t3 = Instant.parse("2026-08-01T12:00:00Z");

        persistFollow(USER_A, USER_B, t1);
        persistFollow(USER_A, USER_C, t3);  // 가장 최근
        persistFollow(USER_A, USER_D, t2);
        persistFollow(USER_D, USER_B, t3);  // follower 가 USER_D — 결과에 없어야 함

        List<Follow> result = followRepository.findFollowingsByFollowerIdDesc(
                USER_A.toString(), null, null, 10);

        assertThat(result).extracting(Follow::getFolloweeId)
                .containsExactly(USER_C, USER_D, USER_B);
    }

    @Test
    @DisplayName("limit 이 데이터 수보다 크면 전체를 반환한다")
    void findFollowingsByFollowerIdDesc_limitLargerThanData() {
        persistFollow(USER_A, USER_B, Instant.now());
        persistFollow(USER_A, USER_C, Instant.now());

        List<Follow> result = followRepository.findFollowingsByFollowerIdDesc(
                USER_A.toString(), null, null, 100);

        assertThat(result).hasSize(2);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private Follow persistFollow(UUID followerId, UUID followeeId, Instant createdAt) {
        Follow follow = Follow.builder().followerId(followerId).followeeId(followeeId).build();
        em.persist(follow);
        em.flush();
        // BaseEntity 의 @CreatedDate 는 auditing 으로 자동 설정되므로,
        // 테스트 시나리오상 원하는 시각으로 강제 세팅한다.
        // merge 는 auditing 이 다시 트리거되어 값이 덮어써지므로 native UPDATE 로 강제한다.
        em.getEntityManager().createNativeQuery("""
                UPDATE follows SET created_at = :ts WHERE id = :id
                """)
                .setParameter("ts", createdAt)
                .setParameter("id", follow.getId())
                .executeUpdate();
        em.flush();
        em.clear();
        Follow refreshed = em.find(Follow.class, follow.getId());
        return refreshed != null ? refreshed : follow;
    }
}
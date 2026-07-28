package com.mopl.playlist.repository;

import com.mopl.global.config.JpaConfig;
import com.mopl.playlist.entity.Playlist;
import com.mopl.playlist.entity.PlaylistSubscription;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PlaylistRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    TestEntityManager em;

    @Autowired
    PlaylistRepository playlistRepository;

    @Autowired
    PlaylistSubscriptionRepository subscriptionRepository;

    private static final UUID OWNER_A      = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OWNER_B      = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID SUBSCRIBER_C = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @BeforeEach
    void insertTestUsers() {
        em.getEntityManager().createNativeQuery("""
                INSERT INTO users (id, created_at, updated_at, email, name, password_hash, role, locked)
                VALUES
                  (:idA, NOW(), NOW(), 'a@test.com', 'UserA', 'hash', 'USER', false),
                  (:idB, NOW(), NOW(), 'b@test.com', 'UserB', 'hash', 'USER', false),
                  (:idC, NOW(), NOW(), 'c@test.com', 'UserC', 'hash', 'USER', false)
                ON CONFLICT DO NOTHING
                """)
                .setParameter("idA", OWNER_A)
                .setParameter("idB", OWNER_B)
                .setParameter("idC", SUBSCRIBER_C)
                .executeUpdate();
        em.flush();
    }

    @Test
    @DisplayName("저장한 플레이리스트를 ID로 조회한다")
    void findById_success() {
        Playlist saved = em.persistAndFlush(playlist(OWNER_A, "제목", "설명"));

        Optional<Playlist> result = playlistRepository.findById(saved.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getTitle()).isEqualTo("제목");
        assertThat(result.get().getOwnerId()).isEqualTo(OWNER_A);
        assertThat(result.get().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("updatedAt ASC — 커서 없이 limit 개수만큼 반환한다")
    void findByUpdatedAtAsc_firstPage_respectsLimit() {
        em.persistAndFlush(playlist(OWNER_A, "A", "a"));
        em.persistAndFlush(playlist(OWNER_A, "B", "b"));
        em.persistAndFlush(playlist(OWNER_A, "C", "c"));
        em.clear();

        List<Playlist> result = playlistRepository
                .findByUpdatedAtAsc(null, null, null, null, null, 2);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("ownerIdEqual 조건으로 소유자 필터링된다")
    void findByUpdatedAtAsc_filterByOwner() {
        em.persistAndFlush(playlist(OWNER_A, "A", "a"));
        em.persistAndFlush(playlist(OWNER_A, "B", "b"));
        em.persistAndFlush(playlist(OWNER_B, "C", "c"));
        em.clear();

        List<Playlist> result = playlistRepository
                .findByUpdatedAtAsc(null, OWNER_A.toString(), null, null, null, 10);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(p -> p.getOwnerId().equals(OWNER_A));
    }

    @Test
    @DisplayName("keywordLike 조건으로 제목 검색된다")
    void findByUpdatedAtAsc_filterByKeyword() {
        em.persistAndFlush(playlist(OWNER_A, "액션 영화 모음", "a"));
        em.persistAndFlush(playlist(OWNER_A, "로맨스 드라마", "b"));
        em.persistAndFlush(playlist(OWNER_A, "액션 히어로", "c"));
        em.clear();

        List<Playlist> result = playlistRepository
                .findByUpdatedAtAsc("액션", null, null, null, null, 10);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(p -> p.getTitle().contains("액션"));
    }

    @Test
    @DisplayName("subscriberIdEqual 조건으로 구독자 필터링된다")
    void findByUpdatedAtAsc_filterBySubscriber() {
        Playlist p1 = em.persistAndFlush(playlist(OWNER_A, "구독한 플리", "a"));
        Playlist p2 = em.persistAndFlush(playlist(OWNER_A, "구독 안 한 플리", "b"));
        em.persistAndFlush(subscription(p1.getId(), SUBSCRIBER_C));
        em.clear();

        List<Playlist> result = playlistRepository
                .findByUpdatedAtAsc(null, null, SUBSCRIBER_C.toString(), null, null, 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(p1.getId());
    }

    @Test
    @DisplayName("updatedAt ASC — updatedAt 오름차순으로 정렬된다")
    void findByUpdatedAtAsc_sortedAscending() {
        Playlist first  = em.persistAndFlush(playlist(OWNER_A, "먼저", "a"));
        Playlist second = em.persistAndFlush(playlist(OWNER_A, "나중", "b"));
        em.clear();

        List<Playlist> result = playlistRepository
                .findByUpdatedAtAsc(null, null, null, null, null, 10);

        assertThat(result.get(0).getId()).isEqualTo(first.getId());
        assertThat(result.get(1).getId()).isEqualTo(second.getId());
    }

    @Test
    @DisplayName("updatedAt DESC — updatedAt 내림차순으로 정렬된다")
    void findByUpdatedAtDesc_sortedDescending() {
        Playlist first  = em.persistAndFlush(playlist(OWNER_A, "먼저", "a"));
        Playlist second = em.persistAndFlush(playlist(OWNER_A, "나중", "b"));
        em.clear();

        List<Playlist> result = playlistRepository
                .findByUpdatedAtDesc(null, null, null, null, null, 10);

        assertThat(result.get(0).getId()).isEqualTo(second.getId());
        assertThat(result.get(1).getId()).isEqualTo(first.getId());
    }

    @Test
    @DisplayName("subscribeCount DESC — 구독 수 내림차순으로 정렬된다")
    void findBySubscriberCountDesc_sortedDescending() {
        Playlist low  = em.persistAndFlush(playlist(OWNER_A, "구독낮음", "a"));
        Playlist high = em.persistAndFlush(playlist(OWNER_A, "구독높음", "b"));
        setSubscriberCount(low, 5L);
        setSubscriberCount(high, 20L);
        em.flush();
        em.clear();

        List<Playlist> result = playlistRepository
                .findBySubscriberCountDesc(null, null, null, null, null, 10);

        assertThat(result.get(0).getTitle()).isEqualTo("구독높음");
        assertThat(result.get(1).getTitle()).isEqualTo("구독낮음");
    }

    @Test
    @DisplayName("countByFilter — 필터 없이 전체 개수를 반환한다")
    void countByFilter_noFilter() {
        em.persistAndFlush(playlist(OWNER_A, "A", "a"));
        em.persistAndFlush(playlist(OWNER_A, "B", "b"));
        em.persistAndFlush(playlist(OWNER_B, "C", "c"));
        em.clear();

        long count = playlistRepository.countByFilter(null, null, null);

        assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("countByFilter — ownerIdEqual 필터 적용 시 해당 소유자 개수만 반환한다")
    void countByFilter_filterByOwner() {
        em.persistAndFlush(playlist(OWNER_A, "A", "a"));
        em.persistAndFlush(playlist(OWNER_A, "B", "b"));
        em.persistAndFlush(playlist(OWNER_B, "C", "c"));
        em.clear();

        long count = playlistRepository.countByFilter(null, OWNER_A.toString(), null);

        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("countByFilter — keywordLike 필터 적용 시 제목 일치 개수만 반환한다")
    void countByFilter_filterByKeyword() {
        em.persistAndFlush(playlist(OWNER_A, "액션 영화", "a"));
        em.persistAndFlush(playlist(OWNER_A, "로맨스", "b"));
        em.persistAndFlush(playlist(OWNER_A, "액션 히어로", "c"));
        em.clear();

        long count = playlistRepository.countByFilter("액션", null, null);

        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("countByFilter — subscriberIdEqual 필터 적용 시 구독한 개수만 반환한다")
    void countByFilter_filterBySubscriber() {
        Playlist p1 = em.persistAndFlush(playlist(OWNER_A, "A", "a"));
        Playlist p2 = em.persistAndFlush(playlist(OWNER_A, "B", "b"));
        em.persistAndFlush(playlist(OWNER_A, "C", "c"));
        em.persistAndFlush(subscription(p1.getId(), SUBSCRIBER_C));
        em.persistAndFlush(subscription(p2.getId(), SUBSCRIBER_C));
        em.clear();

        long count = playlistRepository.countByFilter(null, null, SUBSCRIBER_C.toString());

        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("incrementSubscriberCount — DB 에서 원자적으로 카운트를 증가시킨다")
    void incrementSubscriberCount_atomic() {
        Playlist p = em.persistAndFlush(playlist(OWNER_A, "제목", "설명"));
        em.clear();

        playlistRepository.incrementSubscriberCount(p.getId());

        Playlist updated = playlistRepository.findById(p.getId()).orElseThrow();
        assertThat(updated.getSubscriberCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("decrementSubscriberCount — DB 에서 원자적으로 카운트를 감소시킨다")
    void decrementSubscriberCount_atomic() {
        Playlist p = em.persistAndFlush(playlist(OWNER_A, "제목", "설명"));
        setSubscriberCount(p, 3L);
        em.flush();
        em.clear();

        playlistRepository.decrementSubscriberCount(p.getId());

        Playlist updated = playlistRepository.findById(p.getId()).orElseThrow();
        assertThat(updated.getSubscriberCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("decrementSubscriberCount — subscriberCount 가 0 이면 감소하지 않는다")
    void decrementSubscriberCount_doesNotGoBelowZero() {
        Playlist p = em.persistAndFlush(playlist(OWNER_A, "제목", "설명"));
        em.clear();

        playlistRepository.decrementSubscriberCount(p.getId());

        Playlist updated = playlistRepository.findById(p.getId()).orElseThrow();
        assertThat(updated.getSubscriberCount()).isEqualTo(0L);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private Playlist playlist(UUID ownerId, String title, String desc) {
        return Playlist.builder().ownerId(ownerId).title(title).description(desc).build();
    }

    private PlaylistSubscription subscription(UUID playlistId, UUID subscriberId) {
        return PlaylistSubscription.builder()
                .playlistId(playlistId)
                .subscriberId(subscriberId)
                .build();
    }

    private void setSubscriberCount(Playlist p, long count) {
        ReflectionTestUtils.setField(p, "subscriberCount", count);
    }
}
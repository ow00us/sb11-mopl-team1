package com.mopl.watchingsession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.global.config.JpaConfig;
import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaConfig.class)
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Testcontainers
public class WatchingSessionSnapshotRepositoryTest {

    // 모든 테스트가 컨테이너 하나를 공유
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    TestEntityManager entityManager;

    @Autowired
    WatchingSessionSnapshotRepository repository;

    // users, contents의 fk를 참조하므로 제약을 만족하는 최소한의 참조 대상을 네이티브 쿼리로 생성
    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        entityManager.getEntityManager().createNativeQuery(
                "INSERT INTO users (id, email, password_hash, name, role, locked, created_at, updated_at)"
                    + "VALUES (:id, :email, 'stub', 'stub', 'USER', false, now(), now())")
            .setParameter("id", id)
            .setParameter("email", id + "@test.local")
            .executeUpdate();
        return id;
    }

    private UUID insertUser(String name) {
        UUID id = UUID.randomUUID();
        entityManager.getEntityManager().createNativeQuery(
                "INSERT INTO users (id, email, password_hash, name, role, locked, created_at, updated_at)"
                    + "VALUES (:id, :email, 'stub', :name , 'USER', false, now(), now())")
            .setParameter("id", id)
            .setParameter("email", id + "@test.local")
            .setParameter("name", name)
            .executeUpdate();
        return id;
    }

    private UUID insertContent() {
        UUID id = UUID.randomUUID();
        entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO contents (id, type, source, title, description, average_rating, review_count, watcher_count, created_at, updated_at)"
            + "VALUES (:id, 'MOVIE', 'INTERNAL', 'stub', '테스트 설명', 0.0, 0, 0, now(), now())")
            .setParameter("id", id)
            .executeUpdate();
        return id;
    }

    @Test
    @DisplayName("저장한 시청 세션 스냅샷을 watcherId로 조회")
    void findByWatcherId_success() {
        // given
        UUID watcherId = insertUser();
        UUID contentId = insertContent();
        Instant expiresAt = Instant.now().plus(1, ChronoUnit.MINUTES);
        persistSnapshot(watcherId, contentId, Instant.now(), expiresAt);

        // when
        Optional<WatchingSessionSnapshot> result = repository.findByWatcherId(watcherId);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getContentId()).isEqualTo(contentId);
        assertThat(result.get().getCreatedAt()).isNotNull();
        assertThat(result.get().getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("사용자당 활성 세션은 하나만 허용, 중복 저장 시 예외 발생")
    void save_fail_whenWatcherIdDuplicated() {
        // given
        UUID watcherId = insertUser();
        UUID contentId1 = insertContent();
        UUID contentId2 = insertContent();
        Instant expiresAt = Instant.now().plus(1, ChronoUnit.MINUTES);
        entityManager.persistAndFlush(WatchingSessionSnapshot.builder()
            .watcherId(watcherId)
            .contentId(contentId1)
            .expiresAt(expiresAt)
            .build());

        WatchingSessionSnapshot duplicate = WatchingSessionSnapshot.builder()
            .watcherId(watcherId)
            .contentId(contentId2)
            .expiresAt(expiresAt)
            .build();

        // when & then
        assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("watcherId로 스냅샷 삭제")
    void deleteByWatcherId_success() {
        // given
        UUID watcherId = insertUser();
        UUID contentId = insertContent();
        Instant expiresAt = Instant.now().plus(1, ChronoUnit.MINUTES);
        persistSnapshot(watcherId, contentId, Instant.now(), expiresAt);

        // when
        repository.deleteByWatcherId(watcherId);
        entityManager.flush();

        // then
        assertThat(repository.findByWatcherId(watcherId)).isEmpty();
    }

    @Test
    @DisplayName("삭제 대상 세션이 없어도 예외 없이 끝남")
    void deleteByWatcherId_success_whenNoActiveSession() {
        // given
        UUID watcherIdWithoutSession = UUID.randomUUID();

        // when & then
        assertThatCode(() -> {
            repository.deleteByWatcherId(watcherIdWithoutSession);
            entityManager.flush();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("만료된 세션은 목록에서 제외")
    void findByContentIdFirstPageDesc_excludesExpired() {
        // given
        UUID watcherId1 = insertUser("영수");
        UUID watcherId2 = insertUser("민수");
        UUID contentId = insertContent();
        Instant now = Instant.now();

        persistSnapshot(watcherId1, contentId, now.minusSeconds(10), now.plusSeconds(60)); // 활성세션
        persistSnapshot(watcherId2, contentId, now.minusSeconds(5), now.minusSeconds(1)); // 만료세션
        entityManager.clear();

        // when
        List<WatchingSessionSnapshot> result = repository.findByContentIdFirstPageDesc(
            contentId, null, now, PageRequest.of(0, 10)
        );

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getWatcherId()).isEqualTo(watcherId1);
    }

    @Test
    @DisplayName("watcherNameLike로 이름 부분 일치 검색이 동작한다")
    void findByContentIdFirstPageDesc_filtersByWatcherNameLike() {
        // given
        UUID kimId = insertUser("김철수");
        UUID leeId = insertUser("이영희");
        UUID contentId = insertContent();
        Instant now = Instant.now();

        persistSnapshot(kimId, contentId, now, now.plusSeconds(60));
        persistSnapshot(leeId, contentId, now, now.plusSeconds(60));
        entityManager.clear();

        // when
        List<WatchingSessionSnapshot> result = repository.findByContentIdFirstPageDesc(
            contentId, "김", now, PageRequest.of(0,10)
        );

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getWatcherId()).isEqualTo(kimId);
    }

    @Test
    @DisplayName("커서 이후 데이터를 updatedAt Desc, id Desc 순으로 조회한다")
    void findByContentIdAfterDesc_returnsCorrectOrder() {
        // given
        UUID watcherId1 = insertUser();
        UUID watcherId2 = insertUser();
        UUID watcherId3 = insertUser();
        UUID contentId = insertContent();
        Instant now = Instant.now();

        WatchingSessionSnapshot s1 = persistSnapshot(watcherId1, contentId, now.minusSeconds(30), now.plusSeconds(60));
        WatchingSessionSnapshot s2 = persistSnapshot(watcherId2, contentId, now.minusSeconds(20), now.plusSeconds(60));
        WatchingSessionSnapshot s3 = persistSnapshot(watcherId3, contentId, now.minusSeconds(10), now.plusSeconds(60));
        entityManager.clear();

        // when: s3(가장 최신) 이후부터 조회 -> s2, s1 순으로
        List<WatchingSessionSnapshot> result = repository.findByContentIdAfterDesc(
            contentId, null, now, s3.getUpdatedAt(), s3.getId(), PageRequest.of(0, 10)
        );

        // then
        assertThat(result).extracting(WatchingSessionSnapshot::getId)
            .containsExactly(s2.getId(), s1.getId());
    }

    @Test
    @DisplayName("커서 이후 데이터를 updatedAt Asc, id Asc 순으로 조회한다")
    void findByContentIdAfterAsc_returnsCorrectOrder() {
        // given
        UUID watcherId1 = insertUser();
        UUID watcherId2 = insertUser();
        UUID watcherId3 = insertUser();
        UUID contentId = insertContent();
        Instant now = Instant.now();

        WatchingSessionSnapshot s1 = persistSnapshot(watcherId1, contentId, now.minusSeconds(30), now.plusSeconds(60));
        WatchingSessionSnapshot s2 = persistSnapshot(watcherId2, contentId, now.minusSeconds(20), now.plusSeconds(60));
        WatchingSessionSnapshot s3 = persistSnapshot(watcherId3, contentId, now.minusSeconds(10), now.plusSeconds(60));
        entityManager.clear();

        // when: s1(가장 오래됨) 이후부터 조회 -> s2, s3 순으로
        List<WatchingSessionSnapshot> result = repository.findByContentIdAfterAsc(
            contentId, null, now, s1.getUpdatedAt(), s1.getId(), PageRequest.of(0, 10)
        );

        // then
        assertThat(result).extracting(WatchingSessionSnapshot::getId)
            .containsExactly(s2.getId(), s3.getId());
    }

    @Test
    @DisplayName("다른 콘텐츠의 세션은 조회되지 않음")
    void findByContentIdFirstPageDesc_excludesOtherContent() {
        // given
        UUID watcherId1 = insertUser();
        UUID watcherId2 = insertUser();
        UUID contentId = insertContent();
        UUID contentId2 = insertContent();
        Instant now = Instant.now();

        persistSnapshot(watcherId1, contentId, now, now.plusSeconds(60));
        persistSnapshot(watcherId2, contentId2, now, now.plusSeconds(60));
        entityManager.clear();

        // when
        List<WatchingSessionSnapshot> result = repository.findByContentIdFirstPageDesc(
            contentId, null, now, PageRequest.of(0, 10)
        );

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getWatcherId()).isEqualTo(watcherId1);
    }

    @Test
    @DisplayName("countByContentId는 만료를 제외한 개수를 반환")
    void countByContentId_returnsFilteredCount() {
        // given
        UUID watcherId1 = insertUser();
        UUID watcherId2 = insertUser();
        UUID contentId = insertContent();
        Instant now = Instant.now();

        persistSnapshot(watcherId1, contentId, now, now.plusSeconds(60));   // 활성
        persistSnapshot(watcherId2, contentId, now, now.minusSeconds(1));   // 만료
        entityManager.clear();

        // when
        long count = repository.countByContentId(contentId, null, now);

        // then
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("countByContentId는 watcherNameLike 필터를 반영")
    void countByContentId_filtersByWatcherNameLike() {
        // given
        UUID kimId = insertUser("김철수");
        UUID leeId = insertUser("이영희");
        UUID contentId = insertContent();
        Instant now = Instant.now();

        persistSnapshot(kimId, contentId, now, now.plusSeconds(60));
        persistSnapshot(leeId, contentId, now, now.plusSeconds(60));
        entityManager.clear();

        // when
        long count = repository.countByContentId(contentId, "김", now);

        // then
        assertThat(count).isEqualTo(1L);
    }

    private WatchingSessionSnapshot persistSnapshot(
        UUID watcherId, UUID contentId, Instant updatedAt, Instant expiresAt
    ) {
        WatchingSessionSnapshot snapshot = WatchingSessionSnapshot.builder()
            .watcherId(watcherId)
            .contentId(contentId)
            .expiresAt(expiresAt)
            .build();
        entityManager.persistAndFlush(snapshot);

        entityManager.getEntityManager().createNativeQuery(
                "UPDATE watching_session_snapshots SET updated_at = :updatedAt WHERE id = :id")
            .setParameter("updatedAt", updatedAt)
            .setParameter("id", snapshot.getId())
            .executeUpdate();
        entityManager.clear();

        return entityManager.find(WatchingSessionSnapshot.class, snapshot.getId());
    }

    @Test
    @DisplayName("watcherNameLike에 %가 포함되면 리터럴로 취급되어 전체 매칭되지 않음")
    void findByContentIdFirstPageDesc_escapesPercentWildcard() {
        // given
        UUID watcherId = insertUser("100%김철수");
        UUID otherWatcherId = insertUser("이영희");
        UUID contentId = insertContent();
        Instant now = Instant.now();

        persistSnapshot(watcherId, contentId, now, now.plusSeconds(60));
        persistSnapshot(otherWatcherId, contentId, now, now.plusSeconds(60));
        entityManager.clear();

        // when: "%" 자체를 리터럴로 검색 (서비스단 이스케이프 후 전달된다고 가정)
        List<WatchingSessionSnapshot> result = repository.findByContentIdFirstPageDesc(
            contentId, "100\\%", now, PageRequest.of(0, 10)
        );

        // then: "100%김철수"만 매칭, "이영희"는 안 나옴
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getWatcherId()).isEqualTo(watcherId);
    }

    @Test
    @DisplayName("watcherNameLike 대소문자를 구분하지 않고 검색")
    void findByContentIdFirstPageDescending_isCaseInsensitive() {
        // given
        UUID watcherId = insertUser("Kim철수");
        UUID contentId = insertContent();
        Instant now = Instant.now();

        persistSnapshot(watcherId, contentId, now, now.plusSeconds(60));
        entityManager.clear();

        // when
        List<WatchingSessionSnapshot> result = repository.findByContentIdFirstPageDesc(
            contentId, "kim", now, PageRequest.of(0, 10)
        );

        // then
        assertThat(result).hasSize(1);
    }

}


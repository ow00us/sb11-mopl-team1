package com.mopl.watchingsession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.global.config.JpaConfig;
import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
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
            + "VALUES (:id, 'MOVIE', 'MANUAL', 'stub', '테스트 설명', 0.0, 0, 0, now(), now())")
            .setParameter("id", id)
            .executeUpdate();
        return id;
    }

    private WatchingSessionSnapshot persistSnapshot(
        UUID watcherId, UUID contentId, Instant createdAt, Instant expiresAt
    ) {
        WatchingSessionSnapshot snapshot = WatchingSessionSnapshot.builder()
            .watcherId(watcherId)
            .contentId(contentId)
            .expiresAt(expiresAt)
            .build();
        entityManager.persistAndFlush(snapshot);

        entityManager.getEntityManager().createNativeQuery(
                "UPDATE watching_session_snapshots SET created_at = :createdAt WHERE id = :id")
            .setParameter("createdAt", createdAt)
            .setParameter("id", snapshot.getId())
            .executeUpdate();
        entityManager.clear();

        return entityManager.find(WatchingSessionSnapshot.class, snapshot.getId());
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
    @DisplayName("deleteAllByExpiresAtBefore는 기준 시각보다 먼저 만료된 행만 삭제하고 개수를 반환한다")
    void deleteAllByExpiresAtBefore_success_deletesOnlyExpiredRows() {
        // given
        Instant now = Instant.now();
        UUID expiredWatcher1 = insertUser();
        UUID expiredWatcher2 = insertUser();
        UUID activeWatcher = insertUser();
        UUID contentId = insertContent();

        persistSnapshot(expiredWatcher1, contentId, now.minusSeconds(600), now.minusSeconds(60));
        persistSnapshot(expiredWatcher2, contentId, now.minusSeconds(600), now.minusSeconds(1));
        persistSnapshot(activeWatcher, contentId, now, now.plusSeconds(60));
        entityManager.clear();

        // when
        int deleted = repository.deleteAllByExpiresAtBefore(now);
        entityManager.clear();

        // then
        assertThat(deleted).isEqualTo(2);
        assertThat(repository.findByWatcherId(expiredWatcher1)).isEmpty();
        assertThat(repository.findByWatcherId(expiredWatcher2)).isEmpty();
        assertThat(repository.findByWatcherId(activeWatcher)).isPresent();
    }

    @Test
    @DisplayName("deleteAllByExpiresAtBefore는 삭제 대상이 없으면 0을 반환한다")
    void deleteAllByExpiresAtBefore_returnsZero_whenNothingExpired() {
        // given
        Instant now = Instant.now();
        UUID watcherId = insertUser();
        UUID contentId = insertContent();
        persistSnapshot(watcherId, contentId, now, now.plusSeconds(60));

        // when
        int deleted = repository.deleteAllByExpiresAtBefore(now.minusSeconds(600));

        // then
        assertThat(deleted).isZero();
        assertThat(repository.findByWatcherId(watcherId)).isPresent();
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
    @DisplayName("커서 이후 데이터를 createdAt Desc, id Desc 순으로 조회한다")
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
            contentId, null, now, s3.getCreatedAt(), s3.getId(), PageRequest.of(0, 10)
        );

        // then
        assertThat(result).extracting(WatchingSessionSnapshot::getId)
            .containsExactly(s2.getId(), s1.getId());
    }

    @Test
    @DisplayName("createdAt이 같으면 id DESC로 동률을 깨고, 커서 이후 결과가 중복·누락 없이 반환")
    void findByContentIdAfterDesc_tieBreaksById_whenCreatedAtEqual() {
        // given
        UUID watcherId1 = insertUser();
        UUID watcherId2 = insertUser();
        UUID watcherId3 = insertUser();
        UUID contentId = insertContent();
        Instant now = Instant.now();
        Instant sameCreatedAt = now.minusSeconds(10);

        WatchingSessionSnapshot s1 = persistSnapshot(watcherId1, contentId, sameCreatedAt, now.plusSeconds(60));
        WatchingSessionSnapshot s2 = persistSnapshot(watcherId2, contentId, sameCreatedAt, now.plusSeconds(60));
        WatchingSessionSnapshot s3 = persistSnapshot(watcherId3, contentId, sameCreatedAt, now.plusSeconds(60));
        entityManager.clear();

        // PostgreSQL uuid 정렬은 unsigned byte-order(hex 문자열 정렬)와 같으므로 문자열 비교로 순서를 계산한다.
        List<UUID> sortedIds = new ArrayList<>(List.of(s1.getId(), s2.getId(), s3.getId()));
        sortedIds.sort(Comparator.comparing(UUID::toString));
        UUID smaller = sortedIds.get(0);
        UUID cursor = sortedIds.get(1);
        UUID larger = sortedIds.get(2);

        // when: createdAt이 모두 같으므로 커서 조건은 id 동률 분기(s.createdAt = :cursor AND s.id < :idAfter)만 걸린다.
        List<WatchingSessionSnapshot> result = repository.findByContentIdAfterDesc(
            contentId, null, now, s1.getCreatedAt(), cursor, PageRequest.of(0, 10)
        );

        // then: DESC이므로 cursor보다 id가 작은 행만, 정확히 한 번 반환되어야 한다 (cursor 자신·더 큰 id는 제외).
        assertThat(result).extracting(WatchingSessionSnapshot::getId)
            .containsExactly(smaller);
    }

    @Test
    @DisplayName("커서 이후 데이터를 createdAt Asc, id Asc 순으로 조회한다")
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
            contentId, null, now, s1.getCreatedAt(), s1.getId(), PageRequest.of(0, 10)
        );

        // then
        assertThat(result).extracting(WatchingSessionSnapshot::getId)
            .containsExactly(s2.getId(), s3.getId());
    }

    @Test
    @DisplayName("createdAt이 같으면 id ASC로 동률을 깨고, 커서 이후 결과가 중복·누락 없이 반환")
    void findByContentIdAfterAsc_tieBreaksById_whenCreatedAtEqual() {
        // given
        UUID watcherId1 = insertUser();
        UUID watcherId2 = insertUser();
        UUID watcherId3 = insertUser();
        UUID contentId = insertContent();
        Instant now = Instant.now();
        Instant sameCreatedAt = now.minusSeconds(10);

        WatchingSessionSnapshot s1 = persistSnapshot(watcherId1, contentId, sameCreatedAt, now.plusSeconds(60));
        WatchingSessionSnapshot s2 = persistSnapshot(watcherId2, contentId, sameCreatedAt, now.plusSeconds(60));
        WatchingSessionSnapshot s3 = persistSnapshot(watcherId3, contentId, sameCreatedAt, now.plusSeconds(60));
        entityManager.clear();

        List<UUID> sortedIds = new ArrayList<>(List.of(s1.getId(), s2.getId(), s3.getId()));
        sortedIds.sort(Comparator.comparing(UUID::toString));
        UUID smaller = sortedIds.get(0);
        UUID cursor = sortedIds.get(1);
        UUID larger = sortedIds.get(2);

        // when: createdAt이 모두 같으므로 커서 조건은 id 동률 분기(s.createdAt = :cursor AND s.id > :idAfter)만 걸린다.
        List<WatchingSessionSnapshot> result = repository.findByContentIdAfterAsc(
            contentId, null, now, s1.getCreatedAt(), cursor, PageRequest.of(0, 10)
        );

        // then: ASC이므로 cursor보다 id가 큰 행만, 정확히 한 번 반환되어야 한다 (cursor 자신·더 작은 id는 제외).
        assertThat(result).extracting(WatchingSessionSnapshot::getId)
            .containsExactly(larger);
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

    @Test
    @DisplayName("countGroupedByContentIds는 콘텐츠별 실시간 시청자 수를 정확히 집계한다")
    void countGroupedByContentIds_aggregatesCountsPerContent() {
        // given
        UUID content1 = insertContent();
        UUID content2 = insertContent();
        Instant now = Instant.now();

        persistSnapshot(insertUser(), content1, now, now.plusSeconds(60));
        persistSnapshot(insertUser(), content1, now, now.plusSeconds(60));
        persistSnapshot(insertUser(), content2, now, now.plusSeconds(60));
        entityManager.clear();

        // when
        List<ContentWatcherCountView> result = repository.countGroupedByContentIds(List.of(content1, content2), now);

        // then
        long content1Count = result.stream()
            .filter(view -> view.getContentId().equals(content1))
            .findFirst().orElseThrow().getWatcherCount();
        long content2Count = result.stream()
            .filter(view -> view.getContentId().equals(content2))
            .findFirst().orElseThrow().getWatcherCount();
        assertThat(content1Count).isEqualTo(2L);
        assertThat(content2Count).isEqualTo(1L);
    }

    @Test
    @DisplayName("countGroupedByContentIds는 만료된 세션을 카운트에서 제외한다")
    void countGroupedByContentIds_excludesExpiredSessions() {
        // given
        UUID contentId = insertContent();
        Instant now = Instant.now();

        persistSnapshot(insertUser(), contentId, now, now.plusSeconds(60)); // 활성
        persistSnapshot(insertUser(), contentId, now, now.minusSeconds(1)); // 만료
        entityManager.clear();

        // when
        List<ContentWatcherCountView> result = repository.countGroupedByContentIds(List.of(contentId), now);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getWatcherCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("세션이 하나도 없는 콘텐츠는 결과 리스트에 포함되지 않는다")
    void countGroupedByContentIds_omitsContentsWithoutSessions() {
        // given
        UUID contentWithSession = insertContent();
        UUID contentWithoutSession = insertContent();
        Instant now = Instant.now();

        persistSnapshot(insertUser(), contentWithSession, now, now.plusSeconds(60));
        entityManager.clear();

        // when
        List<ContentWatcherCountView> result = repository.countGroupedByContentIds(
            List.of(contentWithSession, contentWithoutSession), now);

        // then
        assertThat(result).extracting(ContentWatcherCountView::getContentId).containsExactly(contentWithSession);
    }

    @Test
    @DisplayName("renewExpiresAt은 활성 세션의 expiresAt을 새 값으로 연장하고 1을 반환한다")
    void renewExpiresAt_success_extendsActiveSession () {
        // given
        UUID watcherId = insertUser();
        UUID contentId = insertContent();
        Instant now = Instant.now();
        persistSnapshot(watcherId, contentId, now, now.plusSeconds(60));

        Instant newExpiresAt = now.plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);

        // when
        int updated = repository.renewExpiresAt(watcherId, contentId, newExpiresAt);
        entityManager.clear();

        // then
        assertThat(updated).isEqualTo(1);
        WatchingSessionSnapshot renewed = repository.findByWatcherId(watcherId).orElseThrow();
        assertThat(renewed.getExpiresAt()).isEqualTo(newExpiresAt);
    }

    @Test
    @DisplayName("renewExpiresAt은 이미 만료된 세션도 갱신해 부활시킨다")
    void renewExpiresAt_success_revivesAlreadyExpiredSession() {
        // given
        UUID watcherId = insertUser();
        UUID contentId = insertContent();
        Instant now = Instant.now();
        Instant originalExpiresAt = now.minusSeconds(1).truncatedTo(ChronoUnit.MICROS);
        persistSnapshot(watcherId, contentId, now.minusSeconds(60), originalExpiresAt);

        Instant newExpiresAt = now.plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);

        // when
        int updated = repository.renewExpiresAt(watcherId, contentId, newExpiresAt);
        entityManager.clear();

        // then:  DB 갱신이 연속 실패해 expiresAt이 과거로 굳어도, 다음 heartbeat가 정상 도착하면 그대로 부활한다 — 영구 고착 회귀 방지
        assertThat(updated).isEqualTo(1);
        WatchingSessionSnapshot revived = repository.findByWatcherId(watcherId).orElseThrow();
        assertThat(revived.getExpiresAt()).isEqualTo(newExpiresAt);
    }

    @Test
    @DisplayName("renewExpiresAt은 다른 콘텐츠로 전환된 세션에는 적용되지 않고 0을 반환한다")
    void renewExpiresAt_returnsZero_whenContentIdMismatches() {
        // given
        UUID watcherId = insertUser();
        UUID currentContentId = insertContent();
        UUID staleContentId = insertContent();
        Instant now = Instant.now();
        persistSnapshot(watcherId, currentContentId, now, now.plusSeconds(60));

        // when
        int updated = repository.renewExpiresAt(
            watcherId, staleContentId, now.plus(30, ChronoUnit.MINUTES));

        // then
        assertThat(updated).isZero();
    }

    @Test
    @DisplayName("renewExpiresAt은 존재하지 않는 watcherId에 대해 0을 반환한다")
    void renewExpiresAt_returnsZero_whenNoActiveSession() {
        // when
        int updated = repository.renewExpiresAt(
            UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(60));

        // then
        assertThat(updated).isZero();
    }

    @Test
    @DisplayName("renewExpiresAt은 벌크 업데이트라 updatedAt을 변경하지 않는다")
    void renewExpiresAt_doesNotChangeUpdatedAt() {
        // given
        UUID watcherId = insertUser();
        UUID contentId = insertContent();
        Instant now = Instant.now();
        WatchingSessionSnapshot original = persistSnapshot(watcherId, contentId, now, now.plusSeconds(60));
        Instant updatedAtBefore = original.getUpdatedAt();
        entityManager.clear();

        // when
        repository.renewExpiresAt(watcherId, contentId, now.plus(30, ChronoUnit.MINUTES));
        entityManager.clear();

        // then
        WatchingSessionSnapshot renewed = repository.findByWatcherId(watcherId).orElseThrow();
        assertThat(renewed.getUpdatedAt()).isEqualTo(updatedAtBefore);
    }
}


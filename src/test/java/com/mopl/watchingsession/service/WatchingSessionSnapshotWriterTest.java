package com.mopl.watchingsession.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.global.config.JpaConfig;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import com.mopl.watchingsession.service.WatchingSessionSnapshotWriter.UpsertResult;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@ActiveProfiles("test")
@Testcontainers
@DataJpaTest
@Import({WatchingSessionSnapshotWriter.class, JpaConfig.class})
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class WatchingSessionSnapshotWriterTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    WatchingSessionSnapshotWriter writer;

    @Autowired
    WatchingSessionSnapshotRepository repository;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transactionTemplate;

    @AfterEach
    void tearDown() {
        // 커밋된 테스트 데이터 수동 정리
        transactionTemplate.executeWithoutResult(status -> {
            repository.deleteAll();
            entityManager.createNativeQuery("DELETE FROM contents").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM users").executeUpdate();
        });
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery(
                    "INSERT INTO users (id, email, password_hash, name, role, locked, created_at, updated_at)"
                        + "VALUES (:id, :email, 'stub', 'stub', 'USER', false, now(), now())")
                .setParameter("id", id)
                .setParameter("email", id + "@test.local")
                .executeUpdate();
        });
        return id;
    }

    private UUID insertContent() {
        UUID id = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery(
                    "INSERT INTO contents (id, type, source, title, description, average_rating, review_count, watcher_count, created_at, updated_at)"
                        + "VALUES (:id, 'MOVIE', 'MANUAL', 'stub', '테스트 설명', 0.0, 0, 0, now(), now())")
                .setParameter("id", id)
                .executeUpdate();
        });
        return id;
    }

    @Test
    @DisplayName("같은 콘텐츠로 재구독하면 기존 행을 유지한 채 갱신하고, isNewIdentity=false를 반환")
    void upsert_sameContent_refreshesExistingRow_andReportsNotNewIdentity() {
        UUID watcherId = insertUser();
        UUID contentId = insertContent();

        UpsertResult first = writer.upsert(watcherId, contentId, Instant.now().plusSeconds(60));
        Instant firstCreatedAtInDb = repository.findById(first.snapshot().getId()).orElseThrow().getCreatedAt();

        Instant extendedExpiresAt = Instant.now().plusSeconds(120);
        UpsertResult result = writer.upsert(watcherId, contentId, extendedExpiresAt);

        assertThat(repository.count()).isEqualTo(1);
        assertThat(result.isNewIdentity()).isFalse();
        assertThat(result.snapshot().getId()).isEqualTo(first.snapshot().getId());
        assertThat(result.snapshot().getExpiresAt()).isEqualTo(extendedExpiresAt);
        assertThat(result.snapshot().getCreatedAt()).isEqualTo(firstCreatedAtInDb);
    }

    @Test
    @DisplayName("다른 콘텐츠로 전환하면 기존 행을 지우고 새로 삽입하며, isNewIdentity=true를 반환")
    void upsert_differentContent_replacesRow_andReportsNewIdentity() {
        UUID watcherId = insertUser();
        UUID previousContentId = insertContent();
        UUID newContentId = insertContent();

        UpsertResult first = writer.upsert(watcherId, previousContentId, Instant.now().plusSeconds(60));
        UpsertResult result = writer.upsert(watcherId, newContentId, Instant.now().plusSeconds(120));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(result.isNewIdentity()).isTrue();
        assertThat(result.snapshot().getId()).isNotEqualTo(first.snapshot().getId());
        assertThat(result.snapshot().getContentId()).isEqualTo(newContentId);
        assertThat(result.snapshot().getCreatedAt()).isAfterOrEqualTo(first.snapshot().getCreatedAt());
    }

    @Test
    @DisplayName("기존 행이 없으면 새로 삽입하고, isNewIdentity=true를 반환")
    void upsert_insertsNewRow_andReportsNewIdentity() {
        UUID watcherId = insertUser();
        UUID contentId = insertContent();

        UpsertResult result = writer.upsert(watcherId, contentId, Instant.now().plusSeconds(60));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(result.isNewIdentity()).isTrue();
        assertThat(result.snapshot().getWatcherId()).isEqualTo(watcherId);
        assertThat(result.snapshot().getContentId()).isEqualTo(contentId);
    }


    @Test
    @DisplayName("deleteById()는 snapshotId가 일치할 때만 DB에서 제거하고 flush까지 반영")
    void deleteById_removesExistingRow_whenSnapshotIdMatches() {
        // given
        UUID watcherId = insertUser();
        UUID contentId = insertContent();
        UpsertResult result = writer.upsert(watcherId, contentId, Instant.now().plusSeconds(60));
        assertThat(repository.count()).isEqualTo(1);

        // when
        int deletedRows = writer.deleteById(watcherId, result.snapshot().getId());

        // then
        assertThat(deletedRows).isEqualTo(1);
        assertThat(repository.count()).isZero();
        assertThat(repository.findByWatcherId(watcherId)).isEmpty();
    }

    @Test
    @DisplayName("deleteById()는 snapshotId가 일치하지 않으면(이미 다른 세대로 교체됨) 삭제하지 않는다")
    void deleteById_doesNothing_whenSnapshotIdDoesNotMatch() {
        // given
        UUID watcherId = insertUser();
        UUID contentId = insertContent();
        writer.upsert(watcherId, contentId, Instant.now().plusSeconds(60));
        assertThat(repository.count()).isEqualTo(1);

        // when: 존재하지 않는(다른) snapshotId로 삭제 시도
        int deletedRows = writer.deleteById(watcherId, UUID.randomUUID());

        // then: 아무것도 지워지지 않음 - 이 조건부 삭제가 막으려는 정확한 시나리오
        assertThat(deletedRows).isZero();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findByWatcherId(watcherId)).isPresent();
    }

    @Test
    @DisplayName("deleteById()는 해당 watcher의 스냅샷이 없으면 예외 없이 0을 반환한다")
    void deleteById_doesNothing_whenNoRowExists() {
        // given
        UUID watcherId = UUID.randomUUID(); // 스냅샷을 만든 적 없는 임의의 watcherId

        // when & then
        int deletedRows = writer.deleteById(watcherId, UUID.randomUUID());

        assertThat(deletedRows).isZero();
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("다른 watcher의 스냅샷은 deleteById()의 영향을 받지 않는다")
    void deleteById_onlyRemovesTargetWatchersRow() {
        // given
        UUID watcherId1 = insertUser();
        UUID watcherId2 = insertUser();
        UUID contentId = insertContent();
        UpsertResult result1 = writer.upsert(watcherId1, contentId, Instant.now().plusSeconds(60));
        writer.upsert(watcherId2, contentId, Instant.now().plusSeconds(60));
        assertThat(repository.count()).isEqualTo(2);

        // when
        int deletedRows = writer.deleteById(watcherId1, result1.snapshot().getId());

        // then
        assertThat(deletedRows).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findByWatcherId(watcherId1)).isEmpty();
        assertThat(repository.findByWatcherId(watcherId2)).isPresent();
    }

    @Test
    @DisplayName("deleteAllByIdInAndExpiresAtBefore()는 Repository의 동일 이름 메서드에 위임한다")
    void deleteAllByIdInAndExpiresAtBefore_delegatesToRepository() {
        UUID watcherId = insertUser();
        UUID contentId = insertContent();
        Instant now = Instant.now();
        UpsertResult result = writer.upsert(watcherId, contentId, now.minusSeconds(1));

        int deleted = writer.deleteAllByIdInAndExpiresAtBefore(List.of(result.snapshot().getId()), now);

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findByWatcherId(watcherId)).isEmpty();
    }
}

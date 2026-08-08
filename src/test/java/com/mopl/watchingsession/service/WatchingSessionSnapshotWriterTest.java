package com.mopl.watchingsession.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.global.config.JpaConfig;
import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
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
                        + "VALUES (:id, 'MOVIE', 'INTERNAL', 'stub', '테스트 설명', 0.0, 0, 0, now(), now())")
                .setParameter("id", id)
                .executeUpdate();
        });
        return id;
    }

    @Test
    @DisplayName("같은 콘텐츠로 재구독하면 기존 행을 유지한 채 갱신하고 createdAt이 보존됨")
    void upsert_sameContent_refreshesExistingRow() {
        // given
        UUID watcherId = insertUser();
        UUID contentId = insertContent();

        WatchingSessionSnapshot first = writer.upsert(watcherId, contentId, Instant.now().plusSeconds(60));

        // when
        Instant extendedExpiresAt = Instant.now().plusSeconds(120);
        WatchingSessionSnapshot result = writer.upsert(watcherId, contentId, extendedExpiresAt);

        // then
        assertThat(repository.count()).isEqualTo(1);
        assertThat(result.getId()).isEqualTo(first.getId());
        assertThat(result.getExpiresAt()).isEqualTo(extendedExpiresAt);
        assertThat(result.getCreatedAt()).isEqualTo(first.getCreatedAt());
    }

    @Test
    @DisplayName("다른 콘텐츠로 전환하면 기존 행을 지우고 새로 삽입해 createdAt이 전환 시각을 반영")
    void upsert_differentContent_replacesRowAndResetsCreatedAt() {
        // given
        UUID watcherId = insertUser();
        UUID previousContentId = insertContent();
        UUID newContentId = insertContent();

        WatchingSessionSnapshot first = writer.upsert(watcherId, previousContentId, Instant.now().plusSeconds(60));

        // when
        WatchingSessionSnapshot result = writer.upsert(watcherId, newContentId, Instant.now().plusSeconds(120));

        // then
        assertThat(repository.count()).isEqualTo(1);
        assertThat(result.getId()).isNotEqualTo(first.getId());
        assertThat(result.getContentId()).isEqualTo(newContentId);
        assertThat(result.getCreatedAt()).isAfterOrEqualTo(first.getCreatedAt());
    }

    @Test
    @DisplayName("기존 행이 없으면 새로 삽입한다")
    void upsert_insertsNewRow() {
        UUID watcherId = insertUser();
        UUID contentId = insertContent();

        WatchingSessionSnapshot result = writer.upsert(watcherId, contentId, Instant.now().plusSeconds(60));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(result.getWatcherId()).isEqualTo(watcherId);
        assertThat(result.getContentId()).isEqualTo(contentId);
    }

    @Test
    @DisplayName("delete()는 해당 watcher의 스냅샷을 실제로 DB에서 제거하고 flush까지 반영")
    void delete_removesExistingRow() {
        // given
        UUID watcherId = insertUser();
        UUID contentId = insertContent();
        writer.upsert(watcherId, contentId, Instant.now().plusSeconds(60));
        assertThat(repository.count()).isEqualTo(1);

        // when
        writer.delete(watcherId);

        // then:
        assertThat(repository.count()).isZero();
        assertThat(repository.findByWatcherId(watcherId)).isEmpty();
    }

    @Test
    @DisplayName("해당 watcher의 스냅샷이 없으면 delete()는 예외 없이 아무 일도 하지 않음")
    void delete_doesNothing_whenNoRowExists() {
        // given
        UUID watcherId = UUID.randomUUID(); // 스냅샷을 만든 적 없는 임의의 watcherId

        // when & then: 존재하지 않는 행에 대한 삭제는 예외를 던지지 않아야 함
        writer.delete(watcherId);

        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("다른 watcher의 스냅샷은 delete()의 영향을 받지 않음")
    void delete_onlyRemovesTargetWatchersRow() {
        // given
        UUID watcherId1 = insertUser();
        UUID watcherId2 = insertUser();
        UUID contentId = insertContent();
        writer.upsert(watcherId1, contentId, Instant.now().plusSeconds(60));
        writer.upsert(watcherId2, contentId, Instant.now().plusSeconds(60));
        assertThat(repository.count()).isEqualTo(2);

        // when
        writer.delete(watcherId1);

        // then
        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findByWatcherId(watcherId1)).isEmpty();
        assertThat(repository.findByWatcherId(watcherId2)).isPresent();
    }

}

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
    @DisplayName("기존 행이 있으면 새로 삽입하지 않고 갱신")
    void upsert_updatesExistingRow() {
        // given
        UUID watcherId = insertUser();
        UUID previousContentId = insertContent();
        UUID newContentId = insertContent();

        WatchingSessionSnapshot first = writer.upsert(watcherId, previousContentId, Instant.now().plusSeconds(60));
        UUID rowId = first.getId();

        // when
        WatchingSessionSnapshot result = writer.upsert(watcherId, newContentId, Instant.now().plusSeconds(120));

        // then
        assertThat(repository.count()).isEqualTo(1);
        assertThat(result.getId()).isEqualTo(rowId);
        assertThat(result.getContentId()).isEqualTo(newContentId);
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

}

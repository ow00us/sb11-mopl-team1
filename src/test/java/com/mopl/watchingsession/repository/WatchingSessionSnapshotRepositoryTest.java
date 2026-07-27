package com.mopl.watchingsession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.global.config.JpaConfig;
import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    private UUID insertContent() {
        UUID id = UUID.randomUUID();
        entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO contents (id, type, source, title, average_rating, review_count, watcher_count, created_at, updated_at)"
            + "VALUES (:id, 'MOVIE', 'INTERNAL', 'stub', 0.0, 0, 0, now(), now())")
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
        entityManager.persistAndFlush(WatchingSessionSnapshot.builder()
            .watcherId(watcherId)
            .contentId(contentId)
            .expiresAt(expiresAt)
            .build());
        entityManager.clear();

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
        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("watcherId로 스냅샷 삭제")
    void deleteByWatcherId_success() {
        // given
        UUID watcherId = insertUser();
        UUID contentId = insertContent();
        Instant expiresAt = Instant.now().plus(1, ChronoUnit.MINUTES);
        entityManager.persistAndFlush(WatchingSessionSnapshot.builder()
            .watcherId(watcherId)
            .contentId(contentId)
            .expiresAt(expiresAt)
            .build());
        entityManager.clear();

        // when
        repository.deleteByWatcherId(watcherId);
        entityManager.flush();

        // then
        assertThat(repository.findByWatcherId(watcherId)).isEmpty();
    }
}


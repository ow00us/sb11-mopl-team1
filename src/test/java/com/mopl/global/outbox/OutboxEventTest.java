package com.mopl.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class OutboxEventTest {

    private static final Instant NOW = Instant.parse("2026-08-29T03:00:00Z");
    private static final UUID ACTOR_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    @DisplayName("처리자가 없으면 skip은 상태·선점·감사 정보를 바꾸지 않는다")
    void skip_nullActor_keepsState() {
        OutboxEvent event = failedWithClaim();

        assertThatThrownBy(() -> event.skip(null, "업무 영향 확인함", NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("처리자");

        assertUnchanged(event);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    @DisplayName("사유가 없거나 공백뿐이면 skip은 상태·선점·감사 정보를 바꾸지 않는다")
    void skip_invalidReason_keepsState(String reason) {
        OutboxEvent event = failedWithClaim();

        assertThatThrownBy(() -> event.skip(ACTOR_ID, reason, NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("사유");

        assertUnchanged(event);
    }

    @Test
    @DisplayName("유효한 skip은 선점을 해제하고 감사 정보를 남기되 직전 실패 원인은 보존한다")
    void skip_validInput_releasesClaimAndRecordsAudit() {
        OutboxEvent event = failedWithClaim();

        event.skip(ACTOR_ID, "업무 영향 확인함", NOW);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SKIPPED);
        assertThat(event.getClaimOwner()).isNull();
        assertThat(event.getClaimExpiresAt()).isNull();
        assertThat(event.getSkippedBy()).isEqualTo(ACTOR_ID);
        assertThat(event.getSkippedAt()).isEqualTo(NOW);
        assertThat(event.getSkipReason()).isEqualTo("업무 영향 확인함");
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("broker unavailable");
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.minusSeconds(1));
    }

    private OutboxEvent failedWithClaim() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(
            UUID.randomUUID(), "follow.created", 1, aggregateId, NOW.minusSeconds(1),
            "{}", aggregateId.toString(), "NONE", "follow.created:" + aggregateId,
            NOW.minusSeconds(1));
        event.markFailed("broker unavailable");
        // 선점은 repository의 원자 UPDATE가 설정하므로 엔티티 단위 테스트에서는
        // 그 필드만 재현합니다. 영속 상태 검증은 FailureService 통합 테스트가 맡습니다.
        ReflectionTestUtils.setField(event, "claimOwner", "relay-before");
        ReflectionTestUtils.setField(event, "claimExpiresAt", NOW.plusSeconds(30));
        return event;
    }

    private void assertUnchanged(OutboxEvent event) {
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("broker unavailable");
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.minusSeconds(1));
        assertThat(event.getClaimOwner()).isEqualTo("relay-before");
        assertThat(event.getClaimExpiresAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getSkippedBy()).isNull();
        assertThat(event.getSkippedAt()).isNull();
        assertThat(event.getSkipReason()).isNull();
    }
}

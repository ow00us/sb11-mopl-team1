package com.mopl.global.outbox.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.global.outbox.OutboxEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxFailureDtoTest {

    @Test
    @DisplayName("실패 원인이 null이면 문자열로 바꾸지 않고 그대로 반환한다")
    void from_nullLastError_preservesNullAndMetadata() {
        OutboxEvent event = failed(null);

        OutboxFailureDto dto = OutboxFailureDto.from(event);

        assertThat(dto.lastError()).isNull();
        assertThat(dto.eventId()).isEqualTo(event.getEventId());
        assertThat(dto.type()).isEqualTo(event.getType());
        assertThat(dto.occurredAt()).isEqualTo(event.getOccurredAt());
        assertThat(dto.attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("실패 원인이 정확히 500자이면 생략 표시 없이 반환한다")
    void from_lastErrorAtLimit_keepsFullMessage() {
        String error = "가".repeat(500);

        assertThat(OutboxFailureDto.from(failed(error)).lastError()).isEqualTo(error);
    }

    @Test
    @DisplayName("실패 원인이 501자이면 앞 500자와 생략 표시만 반환한다")
    void from_lastErrorBeyondLimit_truncatesOnlyResponse() {
        String prefix = "가".repeat(500);
        OutboxEvent event = failed(prefix + "끝");

        assertThat(OutboxFailureDto.from(event).lastError()).isEqualTo(prefix + "...(생략)");
        assertThat(event.getLastError()).isEqualTo(prefix + "끝");
    }

    private OutboxEvent failed(String lastError) {
        UUID aggregateId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-29T03:00:00Z");
        OutboxEvent event = new OutboxEvent(
            UUID.randomUUID(), "follow.created", 1, aggregateId, occurredAt,
            "{}", aggregateId.toString(), "NONE", "follow.created:" + aggregateId, occurredAt);
        event.markFailed(lastError);
        return event;
    }
}

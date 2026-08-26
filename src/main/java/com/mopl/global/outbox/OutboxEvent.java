package com.mopl.global.outbox;

import com.mopl.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 발행 대기 중이거나 발행을 마친 도메인 이벤트입니다.
 *
 * <p>도메인 상태 변경과 같은 트랜잭션에서 기록해, 상태는 바뀌었는데 이벤트만 유실되는
 * 경우를 없앱니다. 커밋된 행을 relay 가 읽어 Kafka 에 발행합니다.
 *
 * <p>상태 전이는 이 클래스가 소유합니다. 언제 다시 시도할지와 언제 그만둘지는
 * {@link OutboxRetryPolicy} 가 정하고, 이 클래스는 그 결과를 반영만 합니다.
 */
@Getter
@Entity
@Table(name = "outbox_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent extends BaseEntity {

    /**
     * envelope 의 eventId 입니다.
     *
     * <p>relay 가 재발행해도 바꾸지 않습니다. 소비자의 멱등 판정 기준이 이 값입니다.
     */
    @Column(name = "event_id", updatable = false, nullable = false)
    private UUID eventId;

    @Column(name = "type", updatable = false, nullable = false, length = 100)
    private String type;

    @Column(name = "version", updatable = false, nullable = false)
    private int version;

    @Column(name = "aggregate_id", updatable = false, nullable = false)
    private UUID aggregateId;

    /** 도메인 상태 변화가 확정된 시각입니다. 기록 시각이 아닙니다. */
    @Column(name = "occurred_at", updatable = false, nullable = false)
    private Instant occurredAt;

    /**
     * envelope 의 payload 입니다.
     *
     * <p>{@code jsonb} 로 저장합니다. 운영에서 payload 를 조건으로 조회할 수 있고,
     * #235 의 replay 도구가 내용을 확인하기 좋습니다. 다만 jsonb 는 키 순서와 공백을
     * 정규화하므로 원본 바이트가 그대로 보존되지는 않습니다. relay 는 컬럼 값으로
     * envelope 를 다시 만들고 소비자 멱등 판정은 eventId 로 하므로 문제가 없습니다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", updatable = false, nullable = false)
    private String payload;

    /** 카탈로그가 이벤트별로 정한 파티션 키입니다. */
    @Column(name = "partition_key", updatable = false, nullable = false, length = 200)
    private String partitionKey;

    /** 파티션 키로 무엇을 보장하는지입니다. {@code NONE}, {@code AGGREGATE} 또는 업무 키 이름입니다. */
    @Column(name = "ordering_scope", updatable = false, nullable = false, length = 50)
    private String orderingScope;

    /**
     * 도메인 사건을 한 번만 식별하는 키입니다.
     *
     * <p>{@code eventId} 는 envelope 를 식별합니다. 도메인 연산이 두 번 실행되어 envelope 를
     * 각각 새로 만들면 {@code eventId} 가 서로 달라 두 행이 모두 저장되고 이벤트가 두 번
     * 발행됩니다. 이 값은 그 경우를 막습니다.
     *
     * <p>유니크 제약이 걸려 있습니다. 같은 키의 두 번째 기록은 저장되지 않고, 기록이 도메인
     * 트랜잭션 안에서 일어나므로 도메인 변경도 함께 롤백됩니다.
     */
    @Column(name = "deduplication_key", updatable = false, nullable = false, length = 200)
    private String deduplicationKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** 이 시각 이후에 발행을 시도합니다. 최초 기록 시에는 즉시 대상이 되도록 둡니다. */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    /** 이 행을 선점한 relay 인스턴스입니다. 선점 중이 아니면 null 입니다. */
    @Column(name = "claim_owner", length = 100)
    private String claimOwner;

    /** 선점 만료 시각입니다. 지나면 다른 인스턴스가 회수할 수 있습니다. */
    @Column(name = "claim_expires_at")
    private Instant claimExpiresAt;

    /** broker 발행 확인을 받은 시각입니다. */
    @Column(name = "published_at")
    private Instant publishedAt;

    /**
     * 마지막 발행 실패 원인입니다.
     *
     * <p>컬럼은 길이 제한이 없는 {@code text} 입니다. 스택 트레이스를 포함한 긴 메시지가
     * 잘리지 않아야 합니다. 길이를 지정하지 않는 것이 의도입니다.
     */
    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    /** {@link OutboxStatus#SKIPPED} 로 전환한 운영자입니다. */
    @Column(name = "skipped_by")
    private UUID skippedBy;

    /** 건너뛰기로 판단한 시각입니다. */
    @Column(name = "skipped_at")
    private Instant skippedAt;

    /**
     * 건너뛴 사유입니다.
     *
     * <p>비워 둘 수 없습니다. 사유 없는 종결은 나중에 행을 보고 무슨 일이 있었는지 알 수 없게
     * 만들고, 그러면 단순히 지운 것과 다르지 않습니다.
     */
    @Column(name = "skip_reason", columnDefinition = "text")
    private String skipReason;

    /**
     * broker 발행 확인을 받은 뒤 완료로 표시합니다.
     *
     * <p>선점 정보를 비웁니다. 남겨두면 만료된 lease 를 회수하는 조회가 이미 끝난 레코드를
     * 계속 훑습니다.
     */
    public void markPublished(Instant publishedAt) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = publishedAt;
        this.claimOwner = null;
        this.claimExpiresAt = null;
        this.lastError = null;
    }

    /**
     * 발행 시도가 실패했고 다시 시도할 것임을 기록합니다.
     *
     * <p>상태를 발행 대기로 되돌리고 선점을 풉니다. 다음 시도 시각은 호출부가 재시도 정책으로
     * 계산해 넘깁니다. 이 값을 미루지 않으면 원인이 지속되는 실패를 주기마다 다시 두드려
     * 정상 레코드의 발행을 밀어냅니다.
     */
    public void markAttemptFailed(String lastError, Instant nextAttemptAt) {
        this.status = OutboxStatus.PENDING;
        this.attempts = this.attempts + 1;
        this.claimOwner = null;
        this.claimExpiresAt = null;
        this.lastError = lastError;
        this.nextAttemptAt = nextAttemptAt;
    }

    /**
     * 자동 재시도를 그만두고 최종 실패로 남깁니다.
     *
     * <p>삭제하지 않습니다. eventId 와 payload 가 남아 있어야 원인을 고친 뒤 같은 이벤트를
     * 그대로 다시 발행할 수 있습니다.
     */
    public void markFailed(String lastError) {
        this.status = OutboxStatus.FAILED;
        this.attempts = this.attempts + 1;
        this.claimOwner = null;
        this.claimExpiresAt = null;
        this.lastError = lastError;
    }

    /**
     * 최종 실패한 이벤트를 다시 발행 대기로 돌립니다.
     *
     * <p>시도 횟수를 0 으로 되돌립니다. 원인을 고친 뒤 다시 넣는 것이므로, 남은 횟수가 없는
     * 상태로 두면 한 번 실패하고 바로 최종 실패로 되돌아갑니다.
     *
     * <p>{@code lastError} 는 지우지 않습니다. 재처리 후에도 직전 실패 원인이 남아 있어야
     * 같은 실패가 반복되는지 확인할 수 있습니다. 발행에 성공하면 그때 지워집니다.
     */
    public void requeue(Instant now) {
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.claimOwner = null;
        this.claimExpiresAt = null;
        this.nextAttemptAt = now;
    }

    /**
     * 보내지 않기로 하고 종결합니다.
     *
     * <p>{@code lastError} 를 지우지 않습니다. 왜 실패했었는지와 왜 보내지 않기로 했는지는
     * 다른 정보이고, 나중에 판단을 되짚을 때 둘 다 필요합니다.
     *
     * <p>선점 정보를 비웁니다. 남겨두면 만료된 lease 를 회수하는 조회가 이미 끝난 레코드를
     * 계속 훑습니다.
     *
     * @param actorId 건너뛰기로 판단한 운영자
     * @param reason 건너뛴 사유. 비어 있을 수 없습니다
     */
    public void skip(UUID actorId, String reason, Instant now) {
        if (actorId == null) {
            throw new IllegalArgumentException("건너뛰기 처리자는 비워 둘 수 없습니다.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("건너뛰기 사유는 비워 둘 수 없습니다.");
        }
        this.status = OutboxStatus.SKIPPED;
        this.claimOwner = null;
        this.claimExpiresAt = null;
        this.skippedBy = actorId;
        this.skippedAt = now;
        this.skipReason = reason;
    }

    public OutboxEvent(
        UUID eventId,
        String type,
        int version,
        UUID aggregateId,
        Instant occurredAt,
        String payload,
        String partitionKey,
        String orderingScope,
        String deduplicationKey,
        Instant nextAttemptAt
    ) {
        this.eventId = eventId;
        this.type = type;
        this.version = version;
        this.aggregateId = aggregateId;
        this.occurredAt = occurredAt;
        this.payload = payload;
        this.partitionKey = partitionKey;
        this.orderingScope = orderingScope;
        this.deduplicationKey = deduplicationKey;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = nextAttemptAt;
    }
}

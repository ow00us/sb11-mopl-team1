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
 * <p>이 클래스는 저장 모델입니다. 기록 포트, claim·lease, 발행은 후속 이슈에서 붙습니다.
 * 상태 전이 메서드도 그때 함께 정의합니다.
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
     * 사건별 중복 기록 방지 키입니다.
     *
     * <p>예: {@code follow.created:<followId>}. UNIQUE 인덱스로 같은 사건의 두 번째 INSERT 를
     * 데이터베이스가 거부합니다.
     *
     * <p>참조: docs/07-kafka-outbox-contract.md §9
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

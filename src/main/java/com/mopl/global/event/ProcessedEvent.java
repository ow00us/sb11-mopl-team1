package com.mopl.global.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Consumer 가 이미 처리한 이벤트 기록입니다.
 *
 * <p>{@link com.mopl.global.common.BaseEntity} 를 상속하지 않습니다. 처리 기록은 한 번
 * 쓰고 갱신하지 않으므로 {@code updatedAt} 이 의미가 없고, 상속하면 스키마에 쓰지 않는
 * 컬럼이 생깁니다.
 */
@Getter
@Entity
@Table(name = "processed_events")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @CreatedDate
    @Column(updatable = false, nullable = false)
    private Instant createdAt;

    /** 소비 목적입니다. Consumer Group 이름과 같은 값을 씁니다. */
    @Column(name = "consumer_name", updatable = false, nullable = false, length = 100)
    private String consumerName;

    /** envelope 의 eventId 입니다. Outbox 재발행과 Consumer 재시도에서 같은 값이 옵니다. */
    @Column(name = "event_id", updatable = false, nullable = false)
    private UUID eventId;

    /** 진단용입니다. 어떤 종류의 이벤트를 처리했는지 기록만 남깁니다. */
    @Column(name = "event_type", updatable = false, nullable = false, length = 100)
    private String eventType;

    public ProcessedEvent(String consumerName, UUID eventId, String eventType) {
        this.consumerName = consumerName;
        this.eventId = eventId;
        this.eventType = eventType;
    }
}

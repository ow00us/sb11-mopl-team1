package com.mopl.content.search;

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

/**
 * ES 색인 동기화(sync/delete) 이벤트가 레인 큐 포화로 거부됐을 때 남기는 재시도 대기 레코드입니다.
 *
 * <p>{@code ContentSearchKeyedExecutor}의 레인이 가득 차면 {@code ContentSearchSyncListener}가
 * 이벤트를 조용히 버리는 대신 여기에 기록하고, {@code ContentSearchRetryScheduler}가 주기적으로
 * 재적용합니다. 여러 인스턴스가 동시에 재시도를 돌려도 같은 행을 두 번 처리하지 않도록
 * {@code outbox_events}와 같은 claim·lease 구조(claimOwner/claimExpiresAt)를 그대로 따릅니다.
 */
@Getter
@Entity
@Table(name = "content_search_retries")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentSearchRetry extends BaseEntity {

    @Column(name = "content_id", updatable = false, nullable = false)
    private UUID contentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", updatable = false, nullable = false, length = 20)
    private ContentSearchRetryEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ContentSearchRetryStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** 이 시각 이후에 재시도합니다. 최초 기록 시에는 즉시 대상이 되도록 둡니다. */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    /** 이 행을 선점한 인스턴스입니다. 선점 중이 아니면 null입니다. */
    @Column(name = "claim_owner", length = 100)
    private String claimOwner;

    /** 선점 만료 시각입니다. 지나면 다른 인스턴스가 회수할 수 있습니다. */
    @Column(name = "claim_expires_at")
    private Instant claimExpiresAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    public ContentSearchRetry(UUID contentId, ContentSearchRetryEventType eventType, Instant nextAttemptAt) {
        this.contentId = contentId;
        this.eventType = eventType;
        this.status = ContentSearchRetryStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = nextAttemptAt;
    }

    public void markCompleted() {
        this.status = ContentSearchRetryStatus.COMPLETED;
        this.claimOwner = null;
        this.claimExpiresAt = null;
    }

    public void markFailedAttempt(int maxAttempts, Instant nextAttemptAt, String errorMessage) {
        this.attempts += 1;
        this.lastError = errorMessage;
        this.claimOwner = null;
        this.claimExpiresAt = null;
        this.status = (this.attempts >= maxAttempts)
                ? ContentSearchRetryStatus.FAILED
                : ContentSearchRetryStatus.PENDING;
        this.nextAttemptAt = nextAttemptAt;
    }
}

package com.mopl.global.outbox;

/**
 * Outbox 이벤트의 relay 진행 상태입니다.
 *
 * <p>스키마의 {@code ck_outbox_events_status} 체크 제약과 값이 같아야 합니다.
 */
public enum OutboxStatus {

    /** 아직 발행되지 않았습니다. relay 의 조회 대상입니다. */
    PENDING,

    /** Kafka broker 의 발행 확인을 받았습니다. */
    PUBLISHED,

    /**
     * 반복 실패로 자동 재시도를 멈춘 상태입니다.
     *
     * <p>삭제하지 않고 보존해 backlog 지표와 경고 대상으로 삼습니다. 운영자가 원인을
     * 고친 뒤 같은 eventId 로 다시 relay 할 수 있어야 합니다.
     */
    FAILED,

    /**
     * 전달 유효 시한을 넘겨 더 이상 발행하지 않는 상태입니다.
     *
     * <p>{@code premiere.upcoming} 처럼 시작 시각이 지나면 의미가 없어지는 이벤트가
     * 대상입니다. 같은 partition key 의 뒤 이벤트가 이 행 때문에 막히지 않아야 합니다.
     */
    EXPIRED
}

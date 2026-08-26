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
    EXPIRED,

    /**
     * 운영자가 업무 영향을 확인하고 명시적으로 건너뛴 상태입니다.
     *
     * <p>발행에 성공했다는 뜻이 아닙니다. 이벤트를 보내지 않아도 된다는 업무 판단과 그 책임을
     * 남기는 종결 상태입니다. {@link OutboxStatus#PUBLISHED} 로 위장하거나 행을 지워서
     * 처리하지 않는 이유가 여기 있습니다. 둘 다 판단이 있었다는 사실을 지웁니다.
     *
     * <p>처리자, 처리 시각과 사유를 함께 보존합니다. 스키마의
     * {@code ck_outbox_events_skip_audit} 체크 제약이 그 셋을 요구합니다.
     *
     * <p>같은 partition key 의 뒤 이벤트가 이 행 때문에 막히지 않습니다. 앞선 이벤트를
     * 보내지 않기로 했는데 뒤가 계속 막히면 판단의 효과가 없습니다.
     */
    SKIPPED
}

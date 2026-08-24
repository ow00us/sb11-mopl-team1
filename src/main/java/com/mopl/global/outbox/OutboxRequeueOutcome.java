package com.mopl.global.outbox;

/**
 * 최종 실패 이벤트를 다시 발행 대기로 돌린 결과입니다.
 *
 * <p>참·거짓으로 돌려주면 "대상이 없었다"와 "대상은 있는데 되돌릴 상태가 아니었다"가 같은
 * 값이 됩니다. 운영자에게는 전혀 다른 상황입니다. 앞은 eventId 를 잘못 짚은 것이고, 뒤는
 * 이미 누군가 되돌렸거나 발행이 끝난 것입니다.
 */
public enum OutboxRequeueOutcome {

    /** 최종 실패에서 발행 대기로 되돌렸습니다. */
    REQUEUED,

    /** 그 eventId 를 가진 레코드가 없습니다. */
    NOT_FOUND,

    /** 레코드는 있지만 최종 실패 상태가 아닙니다. */
    NOT_FAILED
}

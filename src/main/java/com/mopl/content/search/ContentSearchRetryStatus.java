package com.mopl.content.search;

/**
 * 재시도 대기열 레코드의 진행 상태입니다.
 *
 * <p>스키마의 {@code ck_content_search_retries_status} 체크 제약과 값이 같아야 합니다.
 */
public enum ContentSearchRetryStatus {

    /** 아직 재시도하지 않았습니다. 재시도 스케줄러의 조회 대상입니다. */
    PENDING,

    /** 재시도가 성공했습니다. */
    COMPLETED,

    /**
     * 반복 실패로 자동 재시도를 멈춘 상태입니다.
     *
     * <p>삭제하지 않고 보존해 운영자가 원인을 확인할 수 있게 합니다.
     */
    FAILED
}

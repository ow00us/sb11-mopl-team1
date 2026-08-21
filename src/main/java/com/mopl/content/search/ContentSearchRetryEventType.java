package com.mopl.content.search;

/**
 * 재시도 대기열에 남긴 이벤트가 원래 어떤 종류였는지입니다.
 *
 * <p>재시도 시 이 값에 따라 {@link ContentSearchSyncWorker#sync}와
 * {@link ContentSearchSyncWorker#delete} 중 어떤 걸 다시 호출할지 정합니다. sync는 콘텐츠가
 * 이미 삭제됐으면 스스로 아무 것도 하지 않고 끝나지만 delete는 그렇지 않으므로, 원래
 * 이벤트 종류를 반드시 구분해서 남겨야 합니다.
 */
public enum ContentSearchRetryEventType {
    SYNC,
    DELETE
}

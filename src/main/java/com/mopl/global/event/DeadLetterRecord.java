package com.mopl.global.event;

import java.time.Instant;
import java.util.UUID;

/**
 * DLT 에 보존된 소비 실패 레코드입니다.
 *
 * <p>운영자가 무엇이 왜 실패했는지 확인하고 다시 처리할 대상을 고르는 데 필요한 값만
 * 담습니다. 값 본문은 담지 않습니다. 실패 원인 중 하나가 역직렬화 실패라 항상 읽을 수 있는
 * 것이 아니고, 재발행은 원본 바이트를 그대로 보내야 합니다.
 *
 * @param deadLetterTopic  이 레코드가 있는 DLT 이름
 * @param partition        DLT 파티션. replay 대상을 지목하는 좌표입니다.
 * @param offset           DLT offset. replay 대상을 지목하는 좌표입니다.
 * @param enqueuedAt       DLT 에 적재된 시각
 * @param originalTopic    실패한 원본 토픽. replay 발행 대상입니다.
 * @param partitionKey     원본 레코드의 키. replay 에서도 그대로 씁니다.
 * @param eventId          envelope 의 eventId. 값을 읽지 못하면 {@code null} 입니다.
 * @param exceptionType    실패 예외의 클래스 이름
 * @param exceptionMessage 실패 예외 메시지
 */
public record DeadLetterRecord(
    String deadLetterTopic,
    int partition,
    long offset,
    Instant enqueuedAt,
    String originalTopic,
    String partitionKey,
    UUID eventId,
    String exceptionType,
    String exceptionMessage
) {
}

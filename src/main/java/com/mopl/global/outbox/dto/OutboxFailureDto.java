package com.mopl.global.outbox.dto;

import com.mopl.global.outbox.OutboxEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * 최종 실패한 Outbox 이벤트 한 건의 운영 조회 결과입니다.
 *
 * <p>payload 를 담지 않습니다. Outbox payload 에는 DM 본문처럼 도메인이 사용자에게만
 * 보이기로 한 값이 들어갑니다. 운영자가 원인을 판단하는 데 필요한 것은 어떤 이벤트가 왜
 * 실패했는지이지 그 내용이 아닙니다.
 *
 * @param eventId envelope 의 eventId. 재처리 요청과 소비자 쪽 로그를 잇는 값입니다
 * @param type 이벤트 타입
 * @param occurredAt 도메인 사건이 일어난 시각
 * @param attempts 최종 실패로 남기까지의 발행 시도 횟수
 * @param lastError 마지막 발행 실패 원인
 */
public record OutboxFailureDto(
    UUID eventId,
    String type,
    Instant occurredAt,
    int attempts,
    String lastError
) {

    /**
     * 실패 원인을 이 길이까지만 싣습니다.
     *
     * <p>{@code last_error} 는 길이 제한이 없는 컬럼이고 스택 트레이스가 통째로 들어갑니다.
     * 상한 없이 목록에 실으면 응답 하나가 상한 건수만큼 곱해져 커집니다. 원인 판단에 필요한
     * 것은 메시지 앞부분이고, 전체는 애플리케이션 로그에 남아 있습니다.
     */
    private static final int MAX_LAST_ERROR_LENGTH = 500;

    private static final String TRUNCATION_MARK = "...(생략)";

    public static OutboxFailureDto from(OutboxEvent event) {
        return new OutboxFailureDto(
            event.getEventId(),
            event.getType(),
            event.getOccurredAt(),
            event.getAttempts(),
            truncate(event.getLastError())
        );
    }

    private static String truncate(String lastError) {
        if (lastError == null || lastError.length() <= MAX_LAST_ERROR_LENGTH) {
            return lastError;
        }
        return lastError.substring(0, MAX_LAST_ERROR_LENGTH) + TRUNCATION_MARK;
    }
}

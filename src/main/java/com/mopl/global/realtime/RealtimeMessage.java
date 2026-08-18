package com.mopl.global.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * 인스턴스 사이에서 주고받는 실시간 메시지입니다.
 *
 * <p>WebSocket 과 SSE 연결은 인스턴스마다 따로 유지됩니다. 어떤 인스턴스가 만든 알림이
 * 다른 인스턴스에 연결된 사용자에게 닿으려면 그 사이를 지나는 형식이 필요합니다.
 *
 * <p>payload 를 {@link JsonNode} 로 둡니다. 목적지 도메인이 자기 타입으로 변환하기 전에
 * eventType 을 먼저 확인해야 합니다. 공통 계층이 구체 타입을 알면 도메인이 늘 때마다 이
 * 계층이 함께 바뀝니다.
 *
 * @param messageId        메시지 식별자. 중복 전달을 걸러내는 기준입니다.
 * @param originInstanceId 발행한 인스턴스. 자기 메시지를 되받아 다시 전달하는 것을 막습니다.
 * @param eventType        메시지 종류. 수신 측이 처리 대상을 고르는 기준입니다.
 * @param destination      전달 목적지. 목적지 표기 규칙은 도메인이 정합니다.
 * @param payload          목적지 도메인이 해석할 본문
 */
public record RealtimeMessage(
    UUID messageId,
    String originInstanceId,
    String eventType,
    String destination,
    JsonNode payload
) {
}

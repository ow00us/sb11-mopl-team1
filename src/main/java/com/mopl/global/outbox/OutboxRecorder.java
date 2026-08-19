package com.mopl.global.outbox;

import com.mopl.global.event.EventEnvelope;

/**
 * 도메인이 이벤트를 Outbox 에 기록할 때 쓰는 포트입니다.
 *
 * <p>도메인은 Kafka 도, Outbox 테이블 구조도 알지 않습니다. envelope 와 발행 대상만 넘기면
 * 됩니다. 실제 발행은 relay 가 커밋된 기록을 읽어서 수행합니다.
 *
 * <h2>호출 계약</h2>
 *
 * <p><b>반드시 도메인 트랜잭션 안에서 호출합니다.</b> 도메인 상태 변경과 기록이 같은
 * 트랜잭션에 묶여야 "상태는 바뀌었는데 이벤트만 유실"되는 경우가 없어집니다. 트랜잭션이
 * 없으면 기록이 거부됩니다.
 *
 * <p><b>실제 상태 변화가 있을 때만 호출합니다.</b> 멱등 API 는 이미 처리된 요청에도 성공을
 * 돌려주는데, 그때 이벤트를 기록하면 사용자에게는 아무 일도 없었는데 알림만 다시 갑니다.
 * 서비스가 실제로 무언가를 바꿨는지 판단한 결과를 기준으로 호출해야 합니다.
 *
 * <pre>
 * &#64;Transactional
 * public FollowResult follow(UUID followerId, UUID followeeId) {
 *     FollowResult result = ...;
 *     if (result.created()) {                     // 실제로 새 관계가 생긴 경우에만
 *         outboxRecorder.record(envelope, followId.toString(), "NONE",
 *                               "follow.created:" + followId);
 *     }
 *     return result;
 * }
 * </pre>
 *
 * <p>HTTP 상태 코드나 응답 형태가 아니라 <b>서비스 반환값</b>을 판단 기준으로 삼아야 합니다.
 * 응답 코드는 컨트롤러의 관심사여서, 같은 서비스 결과가 다른 코드로 나갈 수 있습니다.
 *
 * <p><b>eventId 는 호출자가 만들고 재시도해도 바꾸지 않습니다.</b> 이 값이 소비자 멱등
 * 판정의 기준이고, relay 가 재발행해도 유지됩니다.
 *
 * <p><b>deduplicationKey 는 envelope 이 아니라 도메인 사건을 식별합니다.</b> 같은 사건을
 * 다시 기록하려 하면 거부됩니다. {@code eventId} 로는 이 경우를 막을 수 없습니다. 도메인
 * 연산이 두 번 실행되어 envelope 를 각각 새로 만들면 {@code eventId} 가 서로 달라 두 건이
 * 모두 저장되기 때문입니다.
 *
 * <p>키 형식은 이벤트 카탈로그(계약 §9)가 정합니다. 사건을 다시 만들어도 같은 값이 나오는
 * 식별자를 써야 합니다. 시각이나 요청 식별자처럼 시도마다 달라지는 값은 쓸 수 없습니다.
 *
 * <pre>
 * follow.created:&lt;followId&gt;
 * playlist.subscription.created:&lt;subscriptionId&gt;
 * direct-message.created:&lt;directMessageId&gt;
 * </pre>
 */
public interface OutboxRecorder {

    /**
     * envelope 를 Outbox 에 기록합니다.
     *
     * @param envelope       기록할 이벤트. 필수 필드와 버전을 검증합니다.
     * @param partitionKey   이벤트 카탈로그가 정한 파티션 키
     * @param orderingScope  그 키로 보장하는 순서 범위. {@code NONE}, {@code AGGREGATE} 또는 업무 키 이름
     * @param deduplicationKey 도메인 사건을 한 번만 식별하는 키. 카탈로그가 형식을 정합니다
     * @throws com.mopl.global.event.EventContractViolationException envelope 가 계약을 만족하지 않으면
     * @throws org.springframework.transaction.IllegalTransactionStateException 도메인 트랜잭션 없이 호출하면
     * @throws org.springframework.dao.DataIntegrityViolationException 같은 사건이 이미 기록되어 있으면
     */
    void record(
        EventEnvelope envelope, String partitionKey, String orderingScope, String deduplicationKey);
}

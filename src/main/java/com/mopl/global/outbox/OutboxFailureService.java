package com.mopl.global.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최종 실패한 Outbox 이벤트를 확인하고 다시 발행 대기로 돌립니다.
 *
 * <p>운영자가 원인을 고친 뒤 개입하는 경계입니다. 최종 실패는 자동 relay 대상에서 빠지므로,
 * 이 경계가 없으면 이미 커밋된 도메인 변경에 대한 이벤트가 영영 발행되지 않습니다.
 *
 * <p>재처리해도 새 레코드를 만들지 않습니다. eventId 와 partitionKey 가 바뀌면 소비자의 멱등
 * 판정과 파티션 내 순서가 함께 깨집니다. 기존 행의 상태만 되돌립니다.
 *
 * <p>HTTP 를 알지 못합니다. 관리자 API 는
 * {@link com.mopl.global.outbox.controller.OutboxAdminController} 가 이 서비스를 호출하는
 * 형태로 얹혀 있습니다.
 */
@Slf4j
@Service
public class OutboxFailureService {

    private final OutboxEventRepository outboxEventRepository;

    public OutboxFailureService(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    /** 최종 실패한 이벤트를 발생 순으로 조회합니다. 상한을 반드시 받습니다. */
    @Transactional(readOnly = true)
    public List<OutboxEvent> findFailed(int limit) {
        return findFailedInternal(limit);
    }

    /**
     * 조회 본체입니다.
     *
     * <p>{@link #requeueAll(int, Instant)} 이 {@link #findFailed(int)} 를 직접 부르지 않게
     * 분리했습니다. 같은 빈 안에서 호출하면 프록시를 타지 않아 {@code readOnly} 가 적용되지
     * 않는데, 그 사실에 기대어 쓰기가 동작하는 코드는 나중에 읽는 사람을 속입니다.
     */
    private List<OutboxEvent> findFailedInternal(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 은 1 이상이어야 합니다. 실제 " + limit);
        }
        return outboxEventRepository
            .findByStatusOrderByOccurredAtAsc(OutboxStatus.FAILED, Limit.of(limit));
    }

    public long countFailed() {
        return outboxEventRepository.countByStatus(OutboxStatus.FAILED);
    }

    /**
     * 최종 실패한 이벤트 한 건을 다시 발행 대기로 돌립니다.
     *
     * <p>대상을 잠그고 읽습니다. 같은 이벤트에 대한 요청이 동시에 들어와도 전이는 한 번만
     * 일어나고, 뒤에 온 요청은 최종 실패가 아닌 상태를 보고 거절됩니다.
     *
     * @param eventId envelope 의 eventId. 로그와 소비자 쪽에서 확인되는 값입니다.
     * @return 전이 결과
     */
    @Transactional
    public OutboxRequeueOutcome requeue(UUID eventId, Instant now) {
        return outboxEventRepository.findByEventIdForUpdate(eventId)
            .map(event -> {
                if (event.getStatus() != OutboxStatus.FAILED) {
                    return OutboxRequeueOutcome.NOT_FAILED;
                }
                event.requeue(now);
                log.info("Outbox 최종 실패 이벤트를 다시 발행 대기로 돌립니다. eventId={}, type={}",
                    event.getEventId(), event.getType());
                return OutboxRequeueOutcome.REQUEUED;
            })
            .orElse(OutboxRequeueOutcome.NOT_FOUND);
    }

    /**
     * 최종 실패한 이벤트를 상한만큼 다시 발행 대기로 돌립니다.
     *
     * <p>브로커 장애처럼 원인이 하나여서 다수가 함께 실패한 경우에 씁니다. 상한을 받는 이유는
     * 한 번에 되돌린 양이 곧 다음 relay 주기의 부하가 되기 때문입니다.
     *
     * @return 되돌린 건수
     */
    @Transactional
    public int requeueAll(int limit, Instant now) {
        List<OutboxEvent> failed = findFailedInternal(limit);
        failed.forEach(event -> event.requeue(now));

        if (!failed.isEmpty()) {
            log.info("Outbox 최종 실패 이벤트 {}건을 다시 발행 대기로 돌립니다.", failed.size());
        }
        return failed.size();
    }
}

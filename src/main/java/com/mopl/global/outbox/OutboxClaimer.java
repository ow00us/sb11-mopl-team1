package com.mopl.global.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발행할 Outbox 레코드를 선점합니다.
 *
 * <p>여러 애플리케이션 인스턴스가 동시에 relay 를 돌려도 같은 레코드를 두 번 가져가지
 * 않게 하는 것이 목적입니다. 선점한 레코드에는 소유자와 lease 만료 시각을 남기고, relay 가
 * 비정상 종료해 lease 가 만료되면 다른 인스턴스가 회수합니다.
 *
 * <p>발행 성공·실패에 따른 상태 전환은 이 클래스가 다루지 않습니다. #231 relay 이슈에서
 * 붙습니다.
 */
@Slf4j
@Component
public class OutboxClaimer {

    private final OutboxEventRepository outboxEventRepository;

    /**
     * 선점 유효 기간입니다.
     *
     * <p>짧으면 발행이 끝나기 전에 다른 인스턴스가 같은 레코드를 회수해 중복 발행이 늘고,
     * 길면 relay 가 죽었을 때 회수까지 그만큼 지연됩니다. 발행 소요 시간보다 넉넉하고
     * 사람이 기다릴 수 있는 범위로 둡니다.
     */
    private final Duration leaseDuration;

    public OutboxClaimer(
        OutboxEventRepository outboxEventRepository,
        @Value("${mopl.outbox.lease-duration}") Duration leaseDuration
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.leaseDuration = leaseDuration;
    }

    /**
     * 발행 대기 레코드를 batch 크기만큼 선점해 돌려줍니다.
     *
     * <p>전파를 {@code REQUIRES_NEW} 로 둡니다. 선점은 짧고 독립적으로 커밋돼야 합니다.
     * 호출부의 긴 트랜잭션에 참여하면 {@code FOR UPDATE SKIP LOCKED} 로 잡은 잠금이 그
     * 트랜잭션이 끝날 때까지 유지되고, 그동안 다른 인스턴스는 해당 행을 건너뛰기만 합니다.
     *
     * <p>{@code now} 를 파라미터로 받습니다. lease 만료 회수는 시간에 따라 동작이 갈리는데,
     * 내부에서 {@code Instant.now()} 를 읽으면 테스트가 실제로 기다려야 검증됩니다.
     * 운영 호출부는 {@code Instant.now()} 를 넘깁니다.
     *
     * @param owner     선점 소유자. 인스턴스를 구분할 수 있는 값을 넘깁니다.
     * @param batchSize 한 번에 가져올 최대 개수
     * @param now       판정 기준 시각
     * @return 선점한 레코드. 다음 시도 시각이 이른 순입니다. 없으면 빈 목록입니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claim(String owner, int batchSize, Instant now) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 는 1 이상이어야 합니다. 실제 " + batchSize);
        }

        List<UUID> ids = outboxEventRepository.findClaimableIds(now, batchSize);
        if (ids.isEmpty()) {
            return List.of();
        }

        outboxEventRepository.claimByIds(owner, now.plus(leaseDuration), now, ids);

        // 벌크 UPDATE 는 영속성 컨텍스트를 갱신하지 않으므로 갱신된 값을 다시 읽습니다.
        List<OutboxEvent> claimed = outboxEventRepository
            .findByIdInOrderByNextAttemptAtAscIdAsc(ids);

        log.debug("Outbox 선점. owner={}, count={}", owner, claimed.size());
        return claimed;
    }
}

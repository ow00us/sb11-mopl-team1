package com.mopl.watchingsession.dto;

import java.util.UUID;

/**
 * WatchSubscriptionAttributes.consume()의 결과.
 * UNSUBSCRIBE된 subscriptionId에 매핑된 contentId가 있었는지(NO_MAPPING 여부),
 * 있었다면 소비 시점에 그 연결의 활성 구독이었는지(ACTIVE) 낡은 구독이었는지(STALE)를 나타낸다.
 */
public record SubscriptionConsumeResult(Status status, UUID contentId) {

    public enum Status { NO_MAPPING, ACTIVE, STALE }

    public static final SubscriptionConsumeResult NO_MAPPING =
        new SubscriptionConsumeResult(Status.NO_MAPPING, null);

    public static SubscriptionConsumeResult active(UUID contentId) {
        return new SubscriptionConsumeResult(Status.ACTIVE, contentId);
    }

    public static SubscriptionConsumeResult stale(UUID contentId) {
        return new SubscriptionConsumeResult(Status.STALE, contentId);
    }

    public boolean hasMapping() {
        return status != Status.NO_MAPPING;
    }

    public boolean wasActive() {
        return status == Status.ACTIVE;
    }
}

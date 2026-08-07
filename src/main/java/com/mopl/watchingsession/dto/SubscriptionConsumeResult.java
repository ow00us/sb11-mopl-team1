package com.mopl.watchingsession.dto;

import java.util.UUID;

/**
 * WatchSubscriptionAttributes.consume()의 결과.
 * UNSUBSCRIBE된 subscriptionId에 매핑된 contentId가 있었는지 확인
 **/
public record SubscriptionConsumeResult(UUID contentId) {

    public static final SubscriptionConsumeResult NO_MAPPING =
        new SubscriptionConsumeResult(null);

    public static SubscriptionConsumeResult mapped(UUID contentId) {
        return new SubscriptionConsumeResult(contentId);
    }

    public boolean hasMapping() {
        return contentId != null;
    }
}

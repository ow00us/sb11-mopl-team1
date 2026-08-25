package com.mopl.directmessage.entity;

import java.util.Objects;
import java.util.UUID;

public final class ConversationPairKey {

    private static final String DELIMITER = ":";

    private ConversationPairKey() {
    }

    public static String create(
        UUID firstUserId,
        UUID secondUserId
    ) {
        Objects.requireNonNull(
            firstUserId,
            "첫 번째 사용자 ID는 필수입니다."
        );

        Objects.requireNonNull(
            secondUserId,
            "두 번째 사용자 ID는 필수입니다."
        );

        if (firstUserId.equals(secondUserId)) {
            throw new IllegalArgumentException(
                "서로 다른 사용자 ID가 필요합니다."
            );
        }

        String firstValue =
            firstUserId.toString();

        String secondValue =
            secondUserId.toString();

        if (firstValue.compareTo(secondValue) < 0) {
            return firstValue
                + DELIMITER
                + secondValue;
        }

        return secondValue
            + DELIMITER
            + firstValue;
    }
}

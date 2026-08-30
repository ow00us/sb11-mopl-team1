package com.mopl.directmessage.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConversationPairKeyTest {

    private static final UUID SMALLER_USER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    private static final UUID LARGER_USER_ID =
        UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
        );

    @Test
    @DisplayName("사용자 입력 순서와 관계없이 동일한 대화 쌍 Key를 생성")
    void create_reversedUsers_returnsSameKey() {
        // when
        String forward = ConversationPairKey.create(
            SMALLER_USER_ID,
            LARGER_USER_ID
        );

        String reverse = ConversationPairKey.create(
            LARGER_USER_ID,
            SMALLER_USER_ID
        );

        // then
        assertThat(forward)
            .isEqualTo(reverse)
            .isEqualTo(
                SMALLER_USER_ID
                    + ":"
                    + LARGER_USER_ID
            );
    }

    @Test
    @DisplayName("동일한 사용자로는 대화 쌍 Key를 생성할 수 없음")
    void create_sameUser_fails() {
        assertThatIllegalArgumentException()
            .isThrownBy(() ->
                ConversationPairKey.create(
                    SMALLER_USER_ID,
                    SMALLER_USER_ID
                )
            );
    }

    @Test
    @DisplayName("첫 번째 사용자 ID가 없으면 대화 쌍 Key 생성에 실패")
    void create_firstUserMissing_fails() {
        assertThatNullPointerException()
            .isThrownBy(() ->
                ConversationPairKey.create(
                    null,
                    LARGER_USER_ID
                )
            );
    }

    @Test
    @DisplayName("두 번째 사용자 ID가 없으면 대화 쌍 Key 생성에 실패")
    void create_secondUserMissing_fails() {
        assertThatNullPointerException()
            .isThrownBy(() ->
                ConversationPairKey.create(
                    SMALLER_USER_ID,
                    null
                )
            );
    }
}

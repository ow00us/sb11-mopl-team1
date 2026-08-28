package com.mopl.user.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class UserWithdrawalTest {

    @Test
    @DisplayName("사용자를 탈퇴 처리하면 개인정보와 로컬 로그인 수단을 익명화한다")
    void withdraw_anonymizesUser() {
        // given
        UUID userId = UUID.randomUUID();
        Instant deletedAt =
            Instant.parse("2026-08-27T00:00:00Z");

        User user = localUser();

        ReflectionTestUtils.setField(
            user,
            "id",
            userId
        );

        // when
        user.withdraw(deletedAt);

        // then
        assertThat(user.getEmail())
            .isEqualTo(
                "deleted-" + userId + "@deleted.mopl"
            );
        assertThat(user.getPasswordHash())
            .isNull();
        assertThat(user.getName())
            .isEqualTo("탈퇴한 사용자");
        assertThat(user.getProfileImageUrl())
            .isNull();
        assertThat(user.getDeletedAt())
            .isEqualTo(deletedAt);
        assertThat(user.isDeleted())
            .isTrue();
    }

    @Test
    @DisplayName("이미 탈퇴한 사용자는 다시 탈퇴 처리할 수 없다")
    void withdraw_rejectsAlreadyDeletedUser() {
        // given
        User user = localUser();

        ReflectionTestUtils.setField(
            user,
            "id",
            UUID.randomUUID()
        );

        user.withdraw(
            Instant.parse("2026-08-27T00:00:00Z")
        );

        // when & then
        assertThatThrownBy(() ->
            user.withdraw(
                Instant.parse("2026-08-27T01:00:00Z")
            )
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("이미 탈퇴한 사용자입니다.");
    }

    @Test
    @DisplayName("저장되지 않은 사용자는 탈퇴 처리할 수 없다")
    void withdraw_rejectsTransientUser() {
        // given
        User user = localUser();

        // when & then
        assertThatThrownBy(() ->
            user.withdraw(Instant.now())
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(
                "저장되지 않은 사용자는 탈퇴 처리할 수 없습니다."
            );
    }

    @Test
    @DisplayName("회원 탈퇴 시각은 필수이다")
    void withdraw_rejectsNullDeletedAt() {
        // given
        User user = localUser();

        ReflectionTestUtils.setField(
            user,
            "id",
            UUID.randomUUID()
        );

        // when & then
        assertThatThrownBy(() ->
            user.withdraw(null)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("회원 탈퇴 시각은 필수입니다.");
    }

    private User localUser() {
        return User.builder()
            .email("user@example.com")
            .passwordHash("encoded-password")
            .name("탈퇴 전 사용자")
            .profileImageUrl(
                "https://example.com/profile.png"
            )
            .role(UserRole.USER)
            .locked(false)
            .build();
    }
}

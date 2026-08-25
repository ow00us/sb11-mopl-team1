package com.mopl.user.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mopl.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserDtoTest {

    @Test
    @DisplayName("OAuth 내부 식별 이메일은 API 응답에서 null로 변환한다")
    void from_returnsNull_whenEmailUsesInternalOAuthDomain() {
        User user = mock(User.class);

        when(user.getEmail())
            .thenReturn(
                "naver-random-id@oauth.invalid"
            );

        UserDto result =
            UserDto.from(user);

        assertThat(result.email())
            .isNull();
    }

    @Test
    @DisplayName("일반 사용자 이메일은 API 응답에 그대로 포함한다")
    void from_preservesEmail_whenEmailIsPublic() {
        User user = mock(User.class);

        when(user.getEmail())
            .thenReturn(
                "user@example.com"
            );

        UserDto result =
            UserDto.from(user);

        assertThat(result.email())
            .isEqualTo(
                "user@example.com"
            );
    }
}

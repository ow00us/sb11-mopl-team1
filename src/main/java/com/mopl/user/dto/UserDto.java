package com.mopl.user.dto;

import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import java.time.Instant;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 사용자 정보를 API 응답으로 반환할 때 사용하는 DTO
 *
 * 비밀번호 원문과 passwordHash는 보안 정보이므로 절대 응답에 포함하지 않음
 */

public record UserDto(
    UUID id,
    Instant createdAt,

    @Schema(
        types = {
            "string",
            "null"
        },
        description = "사용자 이메일입니다. 이메일이 연결되지 않은 OAuth 사용자는 null입니다."
    )
    String email,

    String name,
    String profileImageUrl,
    UserRole role,
    boolean locked
) {
    // User 엔티티를 API 응답용 UserDto로 변환
    private static final String INTERNAL_OAUTH_EMAIL_DOMAIN =
        "@oauth.invalid";

    public static UserDto from(User user) {
        return new UserDto(
            user.getId(),
            user.getCreatedAt(),
            publicEmail(user.getEmail()),
            user.getName(),
            user.getProfileImageUrl(),
            user.getRole(),
            user.isLocked()
        );
    }

    /**
     * OAuth 전용 사용자의 내부 식별 이메일은 외부 API에 노출하지 않는다.
     *
     * @param email DB에 저장된 사용자 이메일
     * @return 공개 가능한 이메일, 내부 식별 이메일이면 null
     */
    private static String publicEmail(String email) {
        if (email != null
            && email.endsWith(INTERNAL_OAUTH_EMAIL_DOMAIN)) {
            return null;
        }

        return email;
    }

}

package com.mopl.user.dto;

import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import java.time.Instant;
import java.util.UUID;

/**
 * 사용자 정보를 API 응답으로 반환할 때 사용하는 DTO
 *
 * 비밀번호 원문과 passwordHash는 보안 정보이므로 절대 응답에 포함하지 않음
 */

public record UserDto(
    UUID id,
    Instant createdAt,
    String email,
    String name,
    String profileImageUrl,
    UserRole role,
    boolean locked
) {
    // User 엔티티를 API 응답용 UserDto로 변환
    public static UserDto from(User user) {
        return new UserDto(
            user.getId(),
            user.getCreatedAt(),
            user.getEmail(),
            user.getName(),
            user.getProfileImageUrl(),
            user.getRole(),
            user.isLocked()
        );
    }

}

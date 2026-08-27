package com.mopl.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** DM 대상을 찾기 위한 공개 프로필 검색 조건입니다. */
public record UserSearchRequest(
    @NotBlank
    @Size(max = 100)
    String keywordLike,
    String cursor,
    UUID idAfter,
    @Min(1)
    @Max(20)
    int limit
) {
}

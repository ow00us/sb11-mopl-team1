package com.mopl.global.common;

import java.util.UUID;

/**
 * 여러 도메인에서 공통으로 사용하는 사용자 요약 DTO입니다.
 * name·profileImageUrl 은 User 도메인 연동 후 채워집니다.
 */
public record UserSummary(
        UUID userId,
        String name,
        String profileImageUrl
) {}
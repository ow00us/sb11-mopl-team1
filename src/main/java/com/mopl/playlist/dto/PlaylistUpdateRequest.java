package com.mopl.playlist.dto;

import jakarta.validation.constraints.Size;

/**
 * 플레이리스트 부분 수정 요청.
 * <p>
 * {@code null} 또는 빈 문자열/공백만 있는 필드는 무시되고 기존값이 유지됩니다.
 * 예: {@code {"title": "새 제목", "description": ""}} 로 요청하면 description 은 기존값이 유지됩니다.
 * <p>
 * 단, {@code title} 은 {@code @Size(max = 255)} 가 컨트롤러 단에서 먼저 검증되므로
 * 256자 이상 문자열(공백 포함)은 update 로직에 도달하기 전에 400 으로 거절됩니다.
 * <p>
 * 계약 세부는 {@code openapi/mopl-api.yaml} 의 {@code PlaylistUpdateRequest} 스키마를 참조하세요.
 */
public record PlaylistUpdateRequest(
        @Size(max = 255) String title,
        String description
) {}
package com.mopl.user.dto;

/**
 * 회원가입 요청에서 받는 데이터
 * 프론트엔드 요청 JSON 받기 위한 DTO
 * role, locked, passwordHash는 사용자가 가입 요청으로 변경하면 안되는 부분이기 때문에 제외
 * 비밀번호 검증 어노테이션은 Controller 테스트 단계에서 추가
 */
public record UserCreateRequest (
    String name,
    String email,
    String password
) {
}

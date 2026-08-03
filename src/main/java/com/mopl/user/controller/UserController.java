package com.mopl.user.controller;

import com.mopl.user.dto.UserCreateRequest;
import com.mopl.user.dto.UserDto;
import com.mopl.user.service.UserService;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
// /api/users/me 로 변환할 때 살릴 임포트
// import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * 사용자 회원가입 HTTP API를 처리하는 Controller
 *
 * 실제 회원가입 규칙은 UserService에 위임하고
 * Controller는 HTTP 요청·응답과 입력값 검증만 담당
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 이메일과 비밀번호를 이용해 사용자를 생성
     *
     * @Valid가 UserCreateRequest의 Bean Validation을 실행
     * 검증에 실패하면 UserService를 호출하지 않고 400 응답을 반환
     */
    @PostMapping
    @ApiResponse(responseCode = "201", description = "사용자 생성 성공")
    public ResponseEntity<UserDto> signUp(
        @Valid @RequestBody UserCreateRequest request
    ) {
        UserDto response = userService.signUp(request);

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(response);
    }

    /**
     * 사용자 UUID로 사용자 상세 정보를 조회
     *
     * Swagger에 정의된 GET /api/users/{userId} 계약
     * URL 경로에 포함된 userId를 사용해 사용자 정보를 조회
     *
     * @param userId 조회할 사용자 UUID
     * @return 사용자 상세 정보와 200 OK
     */
    @GetMapping("/{userId}")
    public ResponseEntity<UserDto> findUser(
        @PathVariable UUID userId
    ) {
        UserDto response = userService.findUser(userId);

        return ResponseEntity.ok(response);
    }

    /* 추후 선택기능 개발 과정에서 살릴 부분 /api/users/me
    /**
     * 현재 인증된 사용자의 프로필을 조회
     *
     * 클라이언트가 사용자 ID를 path나 request body로 전달하지 않음.
     * JwtAuthenticationFilter가 유효한 액세스 토큰에서 복원한 사용자 UUID를
     * Spring Security의 Authentication principal에서 가져옴
     *
     * 이를 통해 다른 사용자의 UUID를 요청값으로 전달해
     * 자신의 프로필인 것처럼 조회하는 문제를 방지
     *
     * @param userId JWT subject에서 복원된 현재 인증 사용자의 UUID
     * @return 현재 사용자의 프로필 정보와 200 OK

    @GetMapping("/me")
    public ResponseEntity<UserDto> getMyProfile(
        @AuthenticationPrincipal UUID userId
    ) {
        UserDto response = userService.getMyProfile(userId);

        return ResponseEntity.ok(response);
    }
    */

}

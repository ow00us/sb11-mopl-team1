package com.mopl.user.controller;

import com.mopl.user.dto.UserCreateRequest;
import com.mopl.user.dto.UserUpdateRequest;
import com.mopl.user.dto.UserDto;
import com.mopl.user.service.UserService;
import java.util.UUID;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;


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

    /**
     * 사용자의 프로필 정보를 변경
     *
     * Swagger에 정의된 PATCH /api/users/{userId} 계약을 따르며,
     * 요청 형식은 multipart/form-data
     *
     * request 파트에는 변경할 이름을 JSON으로 전달하고,
     * image 파트에는 변경할 프로필 이미지 파일을 선택적으로 전달
     *
     * 실제 수정 권한 확인과 사용자 조회, 이미지 업로드 및
     * 엔티티 변경은 UserService에 위임
     *
     * @param authenticatedUserId JWT 인증 정보에서 가져온 현재 사용자 UUID
     * @param userId URL 경로로 전달된 수정 대상 사용자 UUID
     * @param request 변경할 프로필 정보를 담은 JSON 요청
     * @param image 새 프로필 이미지 파일, 전달하지 않으면 null
     * @return 수정된 사용자 정보와 200 OK
     */
    @PatchMapping(
        value = "/{userId}",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<UserDto> updateUser(
        @AuthenticationPrincipal UUID authenticatedUserId,
        @PathVariable UUID userId,
        @Valid @RequestPart("request") UserUpdateRequest request,
        @RequestPart(value = "image", required = false)
        MultipartFile image
    ) {
        UserDto response = userService.updateUser(
            authenticatedUserId,
            userId,
            request,
            image
        );

        return ResponseEntity.ok(response);
    }

}

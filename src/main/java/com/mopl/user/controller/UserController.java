package com.mopl.user.controller;

import com.mopl.user.dto.UserCreateRequest;
import com.mopl.user.dto.UserDto;
import com.mopl.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}

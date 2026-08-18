package com.mopl.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.dto.JwtDto;
import com.mopl.user.dto.ResetPasswordRequest;
import com.mopl.user.dto.SignInRequest;
import com.mopl.user.dto.UserDto;
import com.mopl.user.entity.UserRole;
import com.mopl.user.cookie.RefreshTokenCookieFactory;
import com.mopl.user.service.IssuedRefreshToken;
import com.mopl.user.service.SignInResult;
import com.mopl.user.service.AuthService;
import com.mopl.user.service.PasswordResetService;
import com.mopl.user.service.RefreshResult;
import com.mopl.user.service.RefreshTokenService;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 이메일 로그인 HTTP API의 요청·응답 형식과 입력값 검증을 확인
 *
 * AuthService는 Mock으로 대체
 * 따라서 이 테스트는 HTTP 상태 코드, JSON 변환, Bean Validation에 집중
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    AuthService authService;

    /**
     * 비밀번호 초기화 비즈니스 로직은 서비스 단위 테스트에서 검증하므로
     * Controller 테스트에서는 Mock으로 교체
     */
    @MockitoBean
    PasswordResetService passwordResetService;

    /**
     * Refresh Token 재발급 비즈니스 로직은 Service 단위 테스트에서
     * 검증하므로 Controller 테스트에서는 Mock으로 교체
     */
    @MockitoBean
    RefreshTokenService refreshTokenService;

    @MockitoBean
    RefreshTokenCookieFactory refreshTokenCookieFactory;

    /**
     * 각 테스트가 SecurityContext에 설정한 인증 정보가
     * 다음 테스트에 남지 않도록 정리
     */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("로그인 성공 시 200, Access Token과 Refresh Token Cookie를 반환한다")
    void signIn_success() throws Exception {
        // given
        UUID userId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        Instant createdAt =
            Instant.parse("2026-07-31T03:00:00Z");

        Map<String, String> request = Map.of(
            "email", "user@example.com",
            "password", "passwordTest1!"
        );

        UserDto userDto =
            new UserDto(
                userId,
                createdAt,
                "user@example.com",
                "테스트 사용자",
                "https://example.com/profile.png",
                UserRole.USER,
                false
            );

        JwtDto jwtDto =
            new JwtDto(
                userDto,
                "access-token"
            );

        IssuedRefreshToken issuedRefreshToken =
            new IssuedRefreshToken(
                "refresh-token",
                Instant.parse("2026-08-18T03:00:00Z")
            );

        when(authService.signIn(
            any(SignInRequest.class)
        ))
            .thenReturn(
                new SignInResult(
                    jwtDto,
                    issuedRefreshToken
                )
            );

        ResponseCookie responseCookie =
            ResponseCookie.from(
                    "REFRESH_TOKEN",
                    "refresh-token"
                )
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(Duration.ofDays(7))
                .build();

        when(refreshTokenCookieFactory.create(
            "refresh-token"
        ))
            .thenReturn(responseCookie);

        // when & then
        mockMvc.perform(
                post("/api/auth/sign-in")
                    .contentType("application/json")
                    .content(
                        objectMapper.writeValueAsString(request)
                    )
            )
            .andExpect(status().isOk())
            .andExpect(
                content().contentType("application/json")
            )
            .andExpect(
                jsonPath("$.accessToken")
                    .value("access-token")
            )
            .andExpect(
                jsonPath("$.userDto.id")
                    .value(userId.toString())
            )
            .andExpect(
                jsonPath("$.userDto.email")
                    .value("user@example.com")
            )
            .andExpect(
                jsonPath("$.userDto.name")
                    .value("테스트 사용자")
            )
            .andExpect(
                jsonPath("$.userDto.profileImageUrl")
                    .value("https://example.com/profile.png")
            )
            .andExpect(
                jsonPath("$.userDto.role")
                    .value("USER")
            )
            .andExpect(
                jsonPath("$.userDto.locked")
                    .value(false)
            )
            /*
             * Refresh Token은 JSON 본문에 포함되면 안된다.
             */
            .andExpect(
                jsonPath("$.refreshToken")
                    .doesNotExist()
            )
            .andExpect(
                jsonPath("$.issuedRefreshToken")
                    .doesNotExist()
            )
            /*
             * Refresh Token은 HttpOnly Cookie로 반환되어야 한다.
             */
            .andExpect(
                cookie().value(
                    "REFRESH_TOKEN",
                    "refresh-token"
                )
            )
            .andExpect(
                cookie().httpOnly(
                    "REFRESH_TOKEN",
                    true
                )
            )
            .andExpect(
                cookie().secure(
                    "REFRESH_TOKEN",
                    false
                )
            )
            .andExpect(
                cookie().path(
                    "REFRESH_TOKEN",
                    "/api/auth"
                )
            )
            .andExpect(
                cookie().maxAge(
                    "REFRESH_TOKEN",
                    Math.toIntExact(
                        Duration.ofDays(7).toSeconds()
                    )
                )
            )
            .andExpect(
                header().string(
                    HttpHeaders.SET_COOKIE,
                    org.hamcrest.Matchers.containsString(
                        "SameSite=Lax"
                    )
                )
            );

        verify(authService).signIn(
            new SignInRequest(
                "user@example.com",
                "passwordTest1!"
            )
        );

        verify(refreshTokenCookieFactory)
            .create("refresh-token");
    }

    @Test
    @DisplayName("이메일이 비어 있으면 400을 반환하고 로그인 처리를 수행하지 않는다")
    void signIn_fail_whenEmailBlank() throws Exception {
        // given
        Map<String, String> request = Map.of(
            "email", "",
            "password", "passwordTest1!"
        );

        // when & then
        mockMvc.perform(post("/api/auth/sign-in")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("COMMON_400_1"))
            .andExpect(jsonPath("$.details.email").exists());

        /*
         * @Valid 검증에서 요청이 차단됐으므로,
         * 서비스의 인증·JWT 발급 로직은 호출되면 안 됨
         */
        verifyNoInteractions(
            authService,
            refreshTokenCookieFactory
        );
    }

    @Test
    @DisplayName("비밀번호 초기화 성공 시 204와 빈 응답 본문을 반환한다")
    void resetPassword_success() throws Exception {
        // given
        Map<String, String> request =
            Map.of(
                "email",
                "USER@EXAMPLE.COM"
            );

        // when & then
        mockMvc.perform(
                post(
                    "/api/auth/reset-password"
                )
                    .contentType(
                        "application/json"
                    )
                    .content(
                        objectMapper
                            .writeValueAsString(
                                request
                            )
                    )
            )
            .andExpect(
                status().isNoContent()
            )
            /*
             * 204 No Content 응답에는 JSON이나 문자열 본문이
             * 포함되어서는 안된다.
             */
            .andExpect(
                content().string("")
            );

        /*
         * Controller는 입력받은 요청 DTO를 Service에 한 번 전달
         *
         * 이메일 소문자 정규화는 Service 책임이므로 Controller 단계에서는
         * 입력된 대문자 이메일이 그대로 DTO에 담겨 전달
         */
        verify(passwordResetService)
            .resetPassword(
                new ResetPasswordRequest(
                    "USER@EXAMPLE.COM"
                )
            );
    }

    @Test
    @DisplayName("비밀번호 초기화 이메일이 공백이면 400을 반환한다")
    void resetPassword_fail_whenEmailIsBlank()
        throws Exception {

        // given
        Map<String, String> request =
            Map.of(
                "email",
                "   "
            );

        // when & then
        mockMvc.perform(
                post(
                    "/api/auth/reset-password"
                )
                    .contentType(
                        "application/json"
                    )
                    .content(
                        objectMapper
                            .writeValueAsString(
                                request
                            )
                    )
            )
            .andExpect(
                status().isBadRequest()
            )
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_400_1")
            )
            .andExpect(
                jsonPath("$.details.email")
                    .exists()
            );

        /*
         * @Valid 검증에서 요청이 차단되므로 사용자 조회,
         * 비밀번호 변경 및 이메일 발송 Service를 호출하지 않는다.
         */
        verifyNoInteractions(
            passwordResetService
        );
    }

    @Test
    @DisplayName("비밀번호 초기화 이메일 형식이 잘못되면 400을 반환한다")
    void resetPassword_fail_whenEmailFormatIsInvalid()
        throws Exception {

        // given
        Map<String, String> request =
            Map.of(
                "email",
                "invalid-email"
            );

        // when & then
        mockMvc.perform(
                post(
                    "/api/auth/reset-password"
                )
                    .contentType(
                        "application/json"
                    )
                    .content(
                        objectMapper
                            .writeValueAsString(
                                request
                            )
                    )
            )
            .andExpect(
                status().isBadRequest()
            )
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_400_1")
            )
            .andExpect(
                jsonPath("$.details.email")
                    .exists()
            );

        verifyNoInteractions(
            passwordResetService
        );
    }

    @Test
    @DisplayName("비밀번호 초기화 대상 이메일이 존재하지 않으면 404를 반환한다")
    void resetPassword_fail_whenUserDoesNotExist()
        throws Exception {

        // given
        Map<String, String> request =
            Map.of(
                "email",
                "missing@example.com"
            );

        /*
         * 현재 OpenAPI 계약은 존재하지 않는 이메일을 404로 정의
         * 실제 사용자 조회 규칙은 Service 테스트에서 별도로 검증
         */
        doThrow(
            new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            )
        )
            .when(passwordResetService)
            .resetPassword(
                any(
                    ResetPasswordRequest.class
                )
            );

        // when & then
        mockMvc.perform(
                post(
                    "/api/auth/reset-password"
                )
                    .contentType(
                        "application/json"
                    )
                    .content(
                        objectMapper
                            .writeValueAsString(
                                request
                            )
                    )
            )
            .andExpect(
                status().isNotFound()
            )
            .andExpect(
                content()
                    .contentType(
                        "application/json"
                    )
            )
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_404_1")
            )
            /*
             * 존재하지 않는 이메일이 응답 본문에 그대로 노출되지
             * 않는지 확인
             */
            .andExpect(
                content().string(
                    org.hamcrest.Matchers
                        .not(
                            org.hamcrest.Matchers
                                .containsString(
                                    "missing@example.com"
                                )
                        )
                )
            );

        verify(passwordResetService)
            .resetPassword(
                new ResetPasswordRequest(
                    "missing@example.com"
                )
            );
    }

    @Test
    @DisplayName("Refresh Token 재발급 성공 시 200과 새 토큰 Cookie를 반환한다")
    void refresh_success() throws Exception {
        // given
        UUID userId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        Instant createdAt =
            Instant.parse("2026-07-31T03:00:00Z");

        UserDto userDto =
            new UserDto(
                userId,
                createdAt,
                "user@example.com",
                "테스트 사용자",
                "https://example.com/profile.png",
                UserRole.USER,
                false
            );

        JwtDto jwtDto =
            new JwtDto(
                userDto,
                "new-access-token"
            );

        IssuedRefreshToken issuedRefreshToken =
            new IssuedRefreshToken(
                "new-refresh-token",
                Instant.parse("2026-08-19T03:00:00Z")
            );

        /*
         * 기존 Refresh Token을 전달하면 Service가 새로운 Access Token과
         * 새로운 Refresh Token 발급 결과를 반환한다고 가정
         */
        when(
            refreshTokenService.refresh(
                "old-refresh-token"
            )
        ).thenReturn(
            new RefreshResult(
                jwtDto,
                issuedRefreshToken
            )
        );

        /*
         * 새 Refresh Token 원문을 로그인과 동일한 보안 속성의
         * HttpOnly Cookie로 변환한다고 가정
         */
        ResponseCookie responseCookie =
            ResponseCookie.from(
                    "REFRESH_TOKEN",
                    "new-refresh-token"
                )
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(Duration.ofDays(7))
                .build();

        when(
            refreshTokenCookieFactory.create(
                "new-refresh-token"
            )
        ).thenReturn(responseCookie);

        Cookie requestCookie =
            new Cookie(
                "REFRESH_TOKEN",
                "old-refresh-token"
            );

        // when & then
        mockMvc.perform(
                post("/api/auth/refresh")
                    .cookie(requestCookie)
            )
            .andExpect(status().isOk())
            .andExpect(
                content().contentType("application/json")
            )
            /*
             * 새 Access Token은 JSON 본문으로 반환
             */
            .andExpect(
                jsonPath("$.accessToken")
                    .value("new-access-token")
            )
            .andExpect(
                jsonPath("$.userDto.id")
                    .value(userId.toString())
            )
            .andExpect(
                jsonPath("$.userDto.email")
                    .value("user@example.com")
            )
            .andExpect(
                jsonPath("$.userDto.name")
                    .value("테스트 사용자")
            )
            .andExpect(
                jsonPath("$.userDto.role")
                    .value("USER")
            )
            .andExpect(
                jsonPath("$.userDto.locked")
                    .value(false)
            )
            /*
             * Refresh Token 원문이나 내부 Service 결과 객체가
             * JSON 응답에 포함되면 안 된다.
             */
            .andExpect(
                jsonPath("$.refreshToken")
                    .doesNotExist()
            )
            .andExpect(
                jsonPath("$.issuedRefreshToken")
                    .doesNotExist()
            )
            /*
             * 기존 Cookie를 교체할 새로운 Refresh Token Cookie를 반환
             */
            .andExpect(
                cookie().value(
                    "REFRESH_TOKEN",
                    "new-refresh-token"
                )
            )
            .andExpect(
                cookie().httpOnly(
                    "REFRESH_TOKEN",
                    true
                )
            )
            .andExpect(
                cookie().secure(
                    "REFRESH_TOKEN",
                    false
                )
            )
            .andExpect(
                cookie().path(
                    "REFRESH_TOKEN",
                    "/api/auth"
                )
            )
            .andExpect(
                cookie().maxAge(
                    "REFRESH_TOKEN",
                    Math.toIntExact(
                        Duration.ofDays(7).toSeconds()
                    )
                )
            )
            .andExpect(
                header().string(
                    HttpHeaders.SET_COOKIE,
                    org.hamcrest.Matchers.containsString(
                        "SameSite=Lax"
                    )
                )
            );

        verify(refreshTokenService)
            .refresh("old-refresh-token");

        verify(refreshTokenCookieFactory)
            .create("new-refresh-token");
    }

    @Test
    @DisplayName("유효하지 않은 Refresh Token이면 401을 반환하고 새 Cookie를 발급하지 않는다")
    void refresh_failWhenTokenIsInvalid()
        throws Exception {

        // given
        Cookie invalidRefreshTokenCookie =
            new Cookie(
                "REFRESH_TOKEN",
                "invalid-refresh-token"
            );

        /*
         * Cookie 자체는 존재하지만 Redis에 세션이 없거나,
         * 만료·폐기·재사용된 Refresh Token이라고 가정한다.
         */
        when(
            refreshTokenService.refresh(
                "invalid-refresh-token"
            )
        ).thenThrow(
            new BusinessException(
                ErrorCode.UNAUTHORIZED,
                "유효하지 않은 Refresh Token입니다."
            )
        );

        // when & then
        mockMvc.perform(
                post("/api/auth/refresh")
                    .cookie(invalidRefreshTokenCookie)
            )
            .andExpect(status().isUnauthorized())
            .andExpect(
                content().contentType("application/json")
            )
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_401_1")
            )
            /*
             * Refresh Token의 구체적인 실패 원인을 응답으로
             * 구분해서 노출하지 않는다.
             */
            .andExpect(
                jsonPath("$.message")
                    .value("유효하지 않은 Refresh Token입니다.")
            )
            /*
             * 재발급에 실패했으므로 새 Refresh Token Cookie가
             * 응답에 포함되면 안 된다.
             */
            .andExpect(
                header().doesNotExist(
                    HttpHeaders.SET_COOKIE
                )
            );

        verify(refreshTokenService)
            .refresh("invalid-refresh-token");

        /*
         * Service가 재발급에 실패했으므로 새 Refresh Token을
         * Cookie로 변환하는 Factory는 실행되면 안 된다.
         */
        verifyNoInteractions(
            refreshTokenCookieFactory
        );
    }

    @Test
    @DisplayName("REFRESH_TOKEN Cookie가 공백이면 400을 반환한다")
    void refresh_failWhenCookieIsBlank() throws Exception {
        // given
        Cookie blankRefreshTokenCookie =
            new Cookie(
                "REFRESH_TOKEN",
                "   "
            );

        // when & then
        mockMvc.perform(
                post("/api/auth/refresh")
                    .cookie(blankRefreshTokenCookie)
            )
            .andExpect(status().isBadRequest())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_400_1")
            )
            /*
             * 유효하지 않은 입력이므로 새로운 Refresh Token Cookie가
             * 응답에 포함되면 안 된다.
             */
            .andExpect(
                header().doesNotExist(
                    HttpHeaders.SET_COOKIE
                )
            );

        /*
         * Controller의 Cookie 입력 검증에서 요청이 차단되므로
         * Service와 Cookie Factory는 호출되면 안 된다.
         */
        verifyNoInteractions(
            refreshTokenService,
            refreshTokenCookieFactory
        );
    }

    @Test
    @DisplayName("REFRESH_TOKEN Cookie가 없으면 400을 반환한다")
    void refresh_failWhenCookieIsMissing() throws Exception {
        // when & then
        mockMvc.perform(
                post("/api/auth/refresh")
            )
            .andExpect(status().isBadRequest());

        /*
         * 필수 Cookie 바인딩 단계에서 요청이 거부되므로
         * 재발급 Service와 Cookie Factory는 호출되면 안 된다.
         */
        verifyNoInteractions(
            refreshTokenService,
            refreshTokenCookieFactory
        );
    }

    @Test
    @DisplayName("로그아웃 성공 시 Refresh Token 삭제 Cookie와 204를 반환한다")
    void signOut_success() throws Exception {
        // given
        UUID authenticatedUserId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        setAuthenticatedUser(authenticatedUserId);

        Cookie refreshTokenCookie =
            new Cookie(
                "REFRESH_TOKEN",
                "current-refresh-token"
            );

        /*
         * Cookie Factory가 기존 Refresh Token Cookie와 같은 속성을 사용하되,
         * 빈 값과 Max-Age=0을 가진 삭제 Cookie를 반환한다고 가정
         */
        ResponseCookie deletionCookie =
            ResponseCookie.from(
                    "REFRESH_TOKEN",
                    ""
                )
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(Duration.ZERO)
                .build();

        when(
            refreshTokenCookieFactory
                .createDeletionCookie()
        ).thenReturn(deletionCookie);

        // when & then
        mockMvc.perform(
                post("/api/auth/sign-out")
                    .cookie(refreshTokenCookie)
            )
            .andExpect(status().isNoContent())
            /*
             * 204 응답에는 JSON이나 문자열 본문이 없어야 한다.
             */
            .andExpect(content().string(""))
            /*
             * 브라우저의 기존 Refresh Token을 삭제할 Cookie를 반환
             */
            .andExpect(
                cookie().value(
                    "REFRESH_TOKEN",
                    ""
                )
            )
            .andExpect(
                cookie().maxAge(
                    "REFRESH_TOKEN",
                    0
                )
            )
            .andExpect(
                cookie().httpOnly(
                    "REFRESH_TOKEN",
                    true
                )
            )
            .andExpect(
                cookie().secure(
                    "REFRESH_TOKEN",
                    false
                )
            )
            .andExpect(
                cookie().path(
                    "REFRESH_TOKEN",
                    "/api/auth"
                )
            )
            .andExpect(
                header().string(
                    HttpHeaders.SET_COOKIE,
                    org.hamcrest.Matchers.containsString(
                        "SameSite=Lax"
                    )
                )
            );

        verify(refreshTokenService)
            .signOut(
                authenticatedUserId,
                "current-refresh-token"
            );

        verify(refreshTokenCookieFactory)
            .createDeletionCookie();
    }

    @Test
    @DisplayName("Refresh Token Cookie가 없어도 멱등하게 로그아웃하고 삭제 Cookie를 반환한다")
    void signOut_succeedsWithoutRefreshTokenCookie()
        throws Exception {

        // given
        UUID authenticatedUserId =
            UUID.fromString(
                "22222222-2222-2222-2222-222222222222"
            );

        setAuthenticatedUser(authenticatedUserId);

        ResponseCookie deletionCookie =
            ResponseCookie.from(
                    "REFRESH_TOKEN",
                    ""
                )
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(Duration.ZERO)
                .build();

        when(
            refreshTokenCookieFactory
                .createDeletionCookie()
        ).thenReturn(deletionCookie);

        // when & then
        mockMvc.perform(
                post("/api/auth/sign-out")
            )
            .andExpect(status().isNoContent())
            .andExpect(content().string(""))
            .andExpect(
                cookie().value(
                    "REFRESH_TOKEN",
                    ""
                )
            )
            .andExpect(
                cookie().maxAge(
                    "REFRESH_TOKEN",
                    0
                )
            );

        /*
         * Cookie가 없으면 null을 그대로 Service에 전달한다.
         * Service는 이를 이미 로그아웃된 상태로 보고 성공 처리
         */
        verify(refreshTokenService)
            .signOut(
                authenticatedUserId,
                null
            );

        /*
         * 브라우저에 오래된 Cookie가 남는 상황을 막기 위해
         * Cookie가 없는 요청에서도 삭제 Cookie는 생성
         */
        verify(refreshTokenCookieFactory)
            .createDeletionCookie();
    }

    /**
     * Controller의 @AuthenticationPrincipal 인자에 전달할
     * UUID 인증 사용자를 SecurityContext에 설정
     *
     * <p>실제 요청에서는 JwtAuthenticationFilter가 Access Token을 검증한 뒤
     * 동일한 형태의 UUID principal을 가진 Authentication을 저장합니다.</p>
     *
     * @param userId 테스트에서 사용할 인증 사용자 UUID
     */
    private void setAuthenticatedUser(UUID userId) {
        var authentication =
            UsernamePasswordAuthenticationToken
                .authenticated(
                    userId,
                    null,
                    List.of()
                );

        SecurityContextHolder
            .getContext()
            .setAuthentication(authentication);
    }
}

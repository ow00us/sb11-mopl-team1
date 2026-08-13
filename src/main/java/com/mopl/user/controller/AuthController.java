package com.mopl.user.controller;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.dto.JwtDto;
import com.mopl.user.dto.SignInRequest;
import com.mopl.user.cookie.RefreshTokenCookieFactory;
import com.mopl.user.config.RefreshTokenCookieProperties;
import com.mopl.user.service.SignInResult;
import com.mopl.user.service.AuthService;
import com.mopl.user.service.RefreshResult;
import com.mopl.user.service.RefreshTokenService;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CookieValue;

/**
 * 이메일 로그인 HTTP API를 처리하는 Controller
 *
 * Controller는 JSON 요청·응답과 입력값 검증만 담당
 * 실제 인증과 JWT 발급 규칙은 AuthService에 위임
 *
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 기존 Refresh Token을 검증하고 Rotation 방식으로
     * Access Token과 Refresh Token을 재발급하는 Service
     */
    private final RefreshTokenService refreshTokenService;

    /**
     * Service가 발급한 Refresh Token 원문을
     * 보안 속성이 적용된 HttpOnly Cookie로 변환
     */
    private final RefreshTokenCookieFactory refreshTokenCookieFactory;

    /**
     * 이메일과 비밀번호로 로그인하고 Access Token과 Refresh Token을 반환
     *
     * @Valid가 SignInRequest의 Bean Validation을 실행
     * 이메일 또는 비밀번호 형식이 잘못되면 서비스 호출 전 400 Bad Request가 반환
     *
     * @param request JSON 형식의 로그인 요청
     * @return 사용자 정보와 Access Token이 담긴 JSON 본문, Refresh Token이 담긴 Set-Cookie 헤더와 200 OK
     */
    @PostMapping("/sign-in")
    @ApiResponse(
        responseCode = "200",
        description = "로그인 성공",
        headers = @Header(
            name = HttpHeaders.SET_COOKIE,
            description = "HttpOnly Refresh Token Cookie",
            schema = @Schema(implementation = String.class)
        ),
        content = @Content(
            schema = @Schema(implementation = JwtDto.class)
        )
    )
    public ResponseEntity<JwtDto> signIn(
        @Valid @RequestBody SignInRequest request
    ) {
        /*
         * AuthService는 JSON 응답용 JwtDto와 Cookie 전달용
         * Refresh Token 발급 결과를 분리하여 반환
         */
        SignInResult result =
            authService.signIn(request);

        /*
         * Refresh Token 원문은 JSON 본문에 넣지 않고
         * HttpOnly Cookie로 변환
         */
        ResponseCookie refreshTokenCookie =
            refreshTokenCookieFactory.create(
                result.issuedRefreshToken().rawToken()
            );

        /*
         * JwtDto만 JSON 본문으로 반환하고 Refresh Token은
         * Set-Cookie 응답 헤더를 통해 전달
         */
        return ResponseEntity.ok()
            .header(
                HttpHeaders.SET_COOKIE,
                refreshTokenCookie.toString()
            )
            .body(result.jwtDto());
    }

    /**
     * Refresh Token Cookie를 이용해 Access Token과 Refresh Token을 재발급
     *
     * <p>요청에 포함된 {@code REFRESH_TOKEN} Cookie의 원문을
     * RefreshTokenService에 전달합니다. Service는 기존 토큰을 검증하고
     * Redis에서 기존 세션과 새로운 세션을 원자적으로 교체합니다.</p>
     *
     * <p>새 Access Token과 사용자 정보는 JSON 본문으로 반환하고,
     * 새로운 Refresh Token 원문은 JSON에 노출하지 않고
     * HttpOnly Cookie로 교체합니다.</p>
     *
     * @param rawRefreshToken REFRESH_TOKEN Cookie로 전달된 기존 Refresh Token
     * @return 새로운 Access Token과 사용자 정보 및 새 Refresh Token Cookie
     */
    @PostMapping("/refresh")
    @ApiResponse(
        responseCode = "200",
        description = "Access Token과 Refresh Token 재발급 성공",
        headers = @Header(
            name = HttpHeaders.SET_COOKIE,
            description = "교체된 HttpOnly Refresh Token Cookie",
            schema = @Schema(implementation = String.class)
        ),
        content = @Content(
            schema = @Schema(implementation = JwtDto.class)
        )
    )
    public ResponseEntity<JwtDto> refresh(
        @Parameter(
            name = RefreshTokenCookieProperties.REQUIRED_COOKIE_NAME,
            description = "재발급에 사용할 Refresh Token",
            required = true,
            in = ParameterIn.COOKIE
        )
        @CookieValue(
            name = RefreshTokenCookieProperties.REQUIRED_COOKIE_NAME,
            required = false
        )
        String rawRefreshToken
    ) {
        /*
         * OpenAPI 계약에서는 필수 REFRESH_TOKEN Cookie가 누락된 요청을
         * 400 Bad Request로 정의
         *
         * @CookieValue의 기본 필수 바인딩을 사용하면
         * MissingRequestCookieException이 발생하지만, 현재 공통 예외 처리기는
         * 해당 예외를 별도로 매핑하지 않아 500으로 처리
         *
         * 따라서 Controller에서 Cookie를 선택적으로 바인딩한 뒤
         * 누락 또는 공백 여부를 직접 확인하여 명시적인 400 오류로 변환
         */
        if (
            rawRefreshToken == null
                || rawRefreshToken.isBlank()
        ) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT,
                "Refresh Token Cookie가 필요합니다."
            );
        }

        /*
         * 기존 Refresh Token을 검증하고 Redis Rotation을 수행
         *
         * 반환 결과에는 JSON 응답용 JwtDto와 새 Cookie 생성에 사용할
         * Refresh Token 발급 결과가 분리되어있음.
         */
        RefreshResult result =
            refreshTokenService.refresh(rawRefreshToken);

        /*
         * 새 Refresh Token 원문은 JSON 본문에 포함하지 않고
         * 로그인과 동일한 보안 속성의 HttpOnly Cookie로 변환
         */
        ResponseCookie refreshTokenCookie =
            refreshTokenCookieFactory.create(
                result.issuedRefreshToken().rawToken()
            );

        /*
         * 새 Access Token과 사용자 정보만 JSON 본문으로 반환
         * 새로운 Refresh Token은 Set-Cookie 헤더로 브라우저에 전달
         */
        return ResponseEntity.ok()
            .header(
                HttpHeaders.SET_COOKIE,
                refreshTokenCookie.toString()
            )
            .body(result.jwtDto());
    }
}

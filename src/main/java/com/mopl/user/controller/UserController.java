package com.mopl.user.controller;

import com.mopl.global.common.CursorResponse;
import com.mopl.user.dto.UserListRequest;
import com.mopl.user.dto.UserCreateRequest;
import com.mopl.user.dto.UserUpdateRequest;
import com.mopl.user.dto.UserLockUpdateRequest;
import com.mopl.user.dto.UserDto;
import com.mopl.user.dto.UserRoleUpdateRequest;
import com.mopl.user.dto.ChangePasswordRequest;
import com.mopl.user.dto.OAuthAccountDto;
import com.mopl.user.dto.OAuthLinkStartResponse;
import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.service.UserService;
import com.mopl.user.service.OAuthAccountManagementService;
import com.mopl.user.security.oauth.link.OAuthLinkIntentSessionStore;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import java.util.List;
import java.util.Locale;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ModelAttribute;


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
    private final OAuthAccountManagementService oauthAccountManagementService;
    private final OAuthLinkIntentSessionStore oauthLinkIntentSessionStore;

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
     * 관리자가 사용자 목록을 커서 페이지네이션으로 조회
     *
     * OpenAPI의 GET /api/users 계약을 처리
     *
     * 쿼리 파라미터는 UserListRequest로 바인딩하고,
     * @Valid를 통해 limit, sortBy, sortDirection을 검증
     *
     * 실제 관리자 권한 검사는 Controller 내부가 아니라
     * SecurityFilterChain에서 Controller 진입 전에 수행
     *
     * @param request 사용자 검색·필터·커서·정렬 조건
     * @return 사용자 목록과 다음 페이지 정보를 포함한 200 OK 응답
     */
    @GetMapping
    @ApiResponse(
        responseCode = "200",
        description = "사용자 목록 조회 성공"
    )
    public ResponseEntity<CursorResponse<UserDto>> findUsers(
        @Valid
        @ParameterObject
        @ModelAttribute // JSON이 아닌 URL 쿼리 파라미터를 UserListRequest에 바인딩
        UserListRequest request
    ) {
        CursorResponse<UserDto> response =
            userService.findUsers(request);

        return ResponseEntity.ok(response);
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

    /**
     * 현재 사용자에게 연결된 OAuth 계정 목록을 조회
     *
     * <p>JWT 인증 정보의 사용자 UUID와 URL 경로의 사용자 UUID를
     * Service에 전달하여 본인의 연결 정보만 조회할 수 있도록 합니다.</p>
     *
     * <p>Provider 사용자 ID와 OAuth Token은 응답에 포함하지 않습니다.</p>
     *
     * @param authenticatedUserId JWT에서 복원한 현재 사용자 UUID
     * @param userId OAuth 연결 계정을 조회할 대상 사용자 UUID
     * @return 연결된 OAuth 계정 목록과 200 OK 응답
     */
    @GetMapping("/{userId}/oauth-accounts")
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "OAuth 연결 계정 목록 조회 성공"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "사용자를 찾을 수 없음"
        )
    })
    public ResponseEntity<List<OAuthAccountDto>>
    getLinkedOAuthAccounts(
        @Parameter(hidden = true)
        @AuthenticationPrincipal
        UUID authenticatedUserId,
        @PathVariable
        UUID userId
    ) {
        List<OAuthAccountDto> response =
            oauthAccountManagementService
                .getLinkedAccounts(
                    authenticatedUserId,
                    userId
                );

        return ResponseEntity.ok(response);
    }

    /**
     * 현재 사용자의 OAuth 계정 연결 인증을 시작
     *
     * <p>본인 여부와 기존 연결 상태를 확인한 뒤 OAuth 연결 의도를
     * 현재 브라우저의 임시 세션에 저장합니다.</p>
     *
     * <p>사용자 입력 URL을 Redirect 대상으로 사용하지 않습니다.
     * Provider enum으로부터 서버 내부의 고정 OAuth 시작 경로만
     * 생성하여 Open Redirect를 방지합니다.</p>
     *
     * @param authenticatedUserId JWT에서 복원한 현재 사용자 UUID
     * @param userId OAuth 계정을 연결할 사용자 UUID
     * @param provider 연결할 OAuth Provider
     * @param request 현재 HTTP 요청
     * @return 프론트엔드가 이동할 OAuth 인증 시작 경로
     */
    @PostMapping(
        "/{userId}/oauth-accounts/{provider}/link"
    )
    @Operation(
        summary = "OAuth 계정 연결 인증 시작",
        description = "연결 의도는 HTTP Session에 저장되므로 "
            + "클라이언트는 Cookie credentials를 포함해 이 API를 호출하고, "
            + "응답으로 받은 authorizationPath로 이동할 때도 "
            + "동일한 세션 쿠키를 유지해야 합니다. "
            + "세션 쿠키가 유지되지 않으면 OAuth callback이 "
            + "계정 연결이 아닌 일반 로그인 흐름으로 처리될 수 있습니다. "
            + "authorizationPath는 백엔드 Origin을 기준으로 이동해야 하는 "
            + "상대 경로입니다."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "OAuth 계정 연결 인증 시작 성공"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "사용자를 찾을 수 없음"
        ),
        @ApiResponse(
            responseCode = "409",
            description = "해당 Provider 계정이 이미 연결됨"
        )
    })
    public ResponseEntity<OAuthLinkStartResponse>
    startOAuthAccountLink(
        @Parameter(hidden = true)
        @AuthenticationPrincipal
        UUID authenticatedUserId,
        @PathVariable
        UUID userId,
        @PathVariable
        OAuthProvider provider,
        @Parameter(hidden = true)
        HttpServletRequest request
    ) {
        /*
         * 검증에 실패하면 세션에 연결 의도를 남기지 않는다.
         */
        oauthAccountManagementService
            .validateLinkStart(
                authenticatedUserId,
                userId,
                provider
            );

        oauthLinkIntentSessionStore.save(
            request,
            userId,
            provider
        );

        String authorizationPath =
            "/oauth2/authorization/"
                + provider
                .name()
                .toLowerCase(
                    Locale.ROOT
                );

        return ResponseEntity.ok(
            new OAuthLinkStartResponse(
                authorizationPath
            )
        );
    }

    /**
     * 현재 사용자에게 연결된 OAuth 계정을 해제
     *
     * <p>JWT 인증 사용자 UUID와 URL의 사용자 UUID를 Service에 전달하여
     * 본인의 OAuth 연결만 해제할 수 있도록 합니다.</p>
     *
     * <p>로컬 비밀번호가 없는 OAuth 전용 사용자는 마지막으로 남은
     * OAuth 로그인 수단을 해제할 수 없습니다.</p>
     *
     * @param authenticatedUserId JWT에서 복원한 현재 사용자 UUID
     * @param userId OAuth 연결을 해제할 대상 사용자 UUID
     * @param provider 연결을 해제할 OAuth Provider
     * @return 응답 본문이 없는 204 No Content
     */
    @DeleteMapping(
        "/{userId}/oauth-accounts/{provider}"
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "204",
            description = "OAuth 계정 연결 해제 성공"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "사용자 또는 OAuth 연결 계정을 찾을 수 없음"
        ),
        @ApiResponse(
            responseCode = "409",
            description = "마지막 로그인 수단이어서 연결 해제할 수 없음"
        )
    })
    public ResponseEntity<Void> unlinkOAuthAccount(
        @Parameter(hidden = true)
        @AuthenticationPrincipal
        UUID authenticatedUserId,
        @PathVariable
        UUID userId,
        @PathVariable
        OAuthProvider provider
    ) {
        oauthAccountManagementService
            .unlinkAccount(
                authenticatedUserId,
                userId,
                provider
            );

        return ResponseEntity
            .noContent()
            .build();
    }

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

    /**
     * 인증된 사용자의 비밀번호를 변경
     *
     * JWT 인증 정보에서 가져온 사용자 UUID와 URL의 대상 사용자 UUID를
     * Service에 전달하여 본인의 비밀번호를 변경하는 요청인지 검사
     *
     * 요청으로 받은 비밀번호 원문은 Controller에서 직접 처리하지 않고
     * UserService가 PasswordEncoder로 인코딩하여 비밀번호 해시만 저장
     *
     * 비밀번호 변경이 완료되면 응답 본문 없이 204 No Content를 반환
     *
     * @param authenticatedUserId JWT 인증 정보에서 가져온 사용자 UUID
     * @param userId 비밀번호를 변경할 대상 사용자의 UUID
     * @param request 새 비밀번호가 담긴 요청
     * @return 응답 본문이 없는 204 No Content 응답
     */
    @PatchMapping("/{userId}/password")
    @ApiResponses({
        @ApiResponse(
            responseCode = "204",
            description = "비밀번호 변경 성공"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "사용자를 찾을 수 없음"
        )
    })
    public ResponseEntity<Void> changePassword(
        @AuthenticationPrincipal UUID authenticatedUserId,
        @PathVariable UUID userId,
        @Valid @RequestBody ChangePasswordRequest request
    ) {
        userService.changePassword(
            authenticatedUserId,
            userId,
            request
        );

        return ResponseEntity.noContent().build();
    }

    /**
     * 관리자가 사용자의 권한을 변경
     *
     * SecurityFilterChain에서 ROLE_ADMIN 권한 검사를 통과한 요청에 대해
     * 대상 사용자 UUID와 새로 적용할 권한을 UserService에 전달
     *
     * 관리자 권한 검사는 Spring MVC의 요청 본문 역직렬화와
     * Bean Validation보다 먼저 수행되어야 한다.
     *
     * 요청의 role에는 UserRole enum에 정의된
     * USER 또는 ADMIN만 전달할 수 있다.
     *
     * 권한 변경이 완료되면 응답 본문 없이
     * 204 No Content를 반환
     *
     * @param userId 권한을 변경할 대상 사용자의 UUID
     * @param request 새로 적용할 사용자 권한이 담긴 요청
     * @return 응답 본문이 없는 204 No Content 응답
     */
    @PatchMapping("/{userId}/role")
    @ApiResponses({
        @ApiResponse(
            responseCode = "204",
            description = "사용자 권한 변경 성공"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "사용자를 찾을 수 없음"
        )
    })
    public ResponseEntity<Void> updateRole(
        @PathVariable UUID userId,
        @Valid @RequestBody UserRoleUpdateRequest request
    ) {
        userService.updateRole(
            userId,
            request
        );

        return ResponseEntity.noContent().build();
    }

    /**
     * 관리자가 사용자 계정의 잠금 상태를 변경
     *
     * SecurityFilterChain에서 ROLE_ADMIN 권한 검사를 통과한 요청에 대해
     * 대상 사용자 UUID와 변경할 잠금 상태를 UserService에 전달
     *
     * 관리자 권한 검사는 Spring MVC의 요청 본문 역직렬화와
     * Bean Validation보다 먼저 수행
     *
     * locked가 true이면 계정을 잠그고,
     * false이면 기존 계정 잠금을 해제
     *
     * 변경이 완료되면 응답 본문 없이 204 No Content를 반환
     *
     * @param userId 잠금 상태를 변경할 대상 사용자의 UUID
     * @param request 새 잠금 상태가 담긴 요청
     * @return 응답 본문이 없는 204 No Content 응답
     */
    @PatchMapping("/{userId}/locked")
    @ApiResponses({
        @ApiResponse(
            responseCode = "204",
            description = "계정 잠금 상태 변경 성공"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "사용자를 찾을 수 없음"
        )
    })
    public ResponseEntity<Void> updateLocked(
        @PathVariable UUID userId,
        @Valid @RequestBody UserLockUpdateRequest request
    ) {
        userService.updateLocked(
            userId,
            request
        );

        return ResponseEntity.noContent().build();
    }
}

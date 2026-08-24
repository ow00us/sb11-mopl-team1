package com.mopl.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.common.CursorResponse;
import com.mopl.user.dto.UserListRequest;
import com.mopl.user.dto.UserCreateRequest;
import com.mopl.user.dto.UserDto;
import com.mopl.user.dto.UserUpdateRequest;
import com.mopl.user.dto.UserLockUpdateRequest;
import com.mopl.user.dto.UserRoleUpdateRequest;
import com.mopl.user.dto.ChangePasswordRequest;
import com.mopl.user.dto.OAuthAccountDto;
import com.mopl.user.entity.UserRole;
import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.service.UserService;
import com.mopl.user.service.OAuthAccountManagementService;
import com.mopl.user.security.oauth.link.OAuthLinkIntentSessionStore;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 회원가입 HTTP API를 검증하는 Controller 테스트
 * <p>
 * UserService는 Mock으로 대체 이 테스트는 HTTP 요청, JSON 변환, Bean Validation, HTTP 상태 코드와 응답 형식만 검증
 */
@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    UserService userService;

    @MockitoBean
    OAuthAccountManagementService oauthAccountManagementService;

    @MockitoBean
    OAuthLinkIntentSessionStore oauthLinkIntentSessionStore;

    /**
     * 테스트 종료 후 인증 정보 제거
     *
     * SecurityContextHolder는 현재 테스트 스레드에 인증 정보를 저장
     *
     * 테스트가 끝난 뒤 인증 정보를 제거하지 않으면 다음 테스트가
     * 이전 테스트의 사용자로 인증된 것처럼 실행될 수 있다.
     */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /*      users/me
    /**
     * SecurityContextHolder는 현재 실행 스레드에 인증 정보를 보관
     *
     * 테스트가 끝난 뒤 인증 정보를 제거하지 않으면
     * 다음 테스트가 이전 테스트의 사용자로 인증된 것처럼 동작할 수 있음.

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }
     */

    @Test
    @DisplayName("회원가입 성공 시 201과 생성된 사용자 정보를 반환한다")
    void signUp_success() throws Exception {
        // given
        UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Instant createdAt = Instant.parse("2026-07-28T03:00:00Z");

        Map<String, String> request = Map.of(
            "name", "테스트 사용자",
            "email", "user@example.com",
            "password", "passwordTest1!"
        );

        UserDto response = new UserDto(
            userId,
            createdAt,
            "user@example.com",
            "테스트 사용자",
            null,
            UserRole.USER,
            false
        );

        when(userService.signUp(any(UserCreateRequest.class))).thenReturn(response);

        // when & then
        mockMvc.perform(post("/api/users")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.id").value(userId.toString()))
            .andExpect(jsonPath("$.email").value("user@example.com"))
            .andExpect(jsonPath("$.name").value("테스트 사용자"))
            .andExpect(jsonPath("$.role").value("USER"))
            .andExpect(jsonPath("$.locked").value(false));

        verify(userService).signUp(
            new UserCreateRequest(
                "테스트 사용자",
                "user@example.com",
                "passwordTest1!"
            )
        );
    }

    /**
     * 관리자 사용자 목록 조회 요청의 쿼리 파라미터가
     * UserListRequest로 정상 바인딩되고 Service 결과가 JSON으로 반환되는지 검증한다.
     *
     * <p>이 테스트 클래스는 Security Filter를 비활성화한 Controller 단위 테스트이므로
     * 관리자 권한 자체는 검증하지 않는다. 관리자 권한 검증은
     * SecurityAccessPolicyTest에서 별도로 수행한다.</p>
     */
    @Test
    @DisplayName("사용자 목록 조회 조건을 전달하면 커서 페이지 응답을 반환한다")
    void findUsers_success() throws Exception {
        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        UUID nextIdAfter =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

        Instant createdAt =
            Instant.parse("2026-07-31T03:00:00Z");

        UserDto user = new UserDto(
            userId,
            createdAt,
            "user@example.com",
            "테스트 사용자",
            "https://example.com/profile.png",
            UserRole.USER,
            false
        );

        /*
         * MockMvc 요청에 전달할 쿼리 파라미터와 동일한 조건이다.
         *
         * cursor와 idAfter는 첫 페이지 조회이므로 null이다.
         */
        UserListRequest request = new UserListRequest(
            "user",
            UserRole.USER,
            false,
            null,
            null,
            20,
            "ASCENDING",
            "email"
        );

        /*
         * Service가 반환할 커서 페이지 응답을 구성한다.
         *
         * 다음 페이지가 존재하므로 nextCursor와 nextIdAfter가 포함된다.
         */
        CursorResponse<UserDto> response = CursorResponse.of(
            List.of(user),
            "bmV4dC1jdXJzb3I=",
            nextIdAfter,
            true,
            3L,
            "email",
            "ASCENDING"
        );

        when(userService.findUsers(request))
            .thenReturn(response);

        // when & then
        mockMvc.perform(
                get("/api/users")
                    .param("emailLike", "user")
                    .param("roleEqual", "USER")
                    .param("locked", "false")
                    .param("limit", "20")
                    .param("sortDirection", "ASCENDING")
                    .param("sortBy", "email")
            )
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.data[0].id").value(userId.toString()))
            .andExpect(jsonPath("$.data[0].createdAt").value(createdAt.toString()))
            .andExpect(jsonPath("$.data[0].email").value("user@example.com"))
            .andExpect(jsonPath("$.data[0].name").value("테스트 사용자"))
            .andExpect(jsonPath("$.data[0].profileImageUrl").value("https://example.com/profile.png"))
            .andExpect(jsonPath("$.data[0].role").value("USER"))
            .andExpect(jsonPath("$.data[0].locked").value(false))
            /*
             * 사용자 응답에 비밀번호 관련 필드가 노출되지 않는지 검증한다.
             */
            .andExpect(jsonPath("$.data[0].password").doesNotExist())
            .andExpect(jsonPath("$.data[0].passwordHash").doesNotExist())
            .andExpect(jsonPath("$.nextCursor").value("bmV4dC1jdXJzb3I="))
            .andExpect(jsonPath("$.nextIdAfter").value(nextIdAfter.toString()))
            .andExpect(jsonPath("$.hasNext").value(true))
            .andExpect(jsonPath("$.totalCount").value(3))
            .andExpect(jsonPath("$.sortBy").value("email"))
            .andExpect(jsonPath("$.sortDirection").value("ASCENDING"));

        verify(userService).findUsers(request);
    }

    /**
     * OpenAPI 계약에서 limit의 최댓값은 100
     *
     * <p>101이 전달되면 Controller 메서드가 실행되기 전에
     * UserListRequest의 Bean Validation에서 요청을 거부해야 한다.</p>
     */
    @Test
    @DisplayName("사용자 목록 조회 개수가 100을 초과하면 400을 반환한다")
    void findUsers_fail_whenLimitExceedsMaximum() throws Exception {
        // when & then
        mockMvc.perform(
                get("/api/users")
                    .param("limit", "101")
                    .param("sortDirection", "ASCENDING")
                    .param("sortBy", "createdAt")
            )
            .andExpect(status().isBadRequest());

        /*
         * 요청 DTO 검증 단계에서 거부되므로
         * UserService는 호출되지 않아야 한다.
         */
        verifyNoInteractions(userService);
    }

    /**
     * limit, sortDirection, sortBy는 OpenAPI에서 필수인 쿼리 파라미터
     *
     * <p>필수 조건이 전달되지 않으면 UserListRequest 검증에 실패하고
     * 400 Bad Request가 반환되어야 한다.</p>
     */
    @Test
    @DisplayName("사용자 목록 조회 필수 조건이 누락되면 400을 반환한다")
    void findUsers_fail_whenRequiredParametersAreMissing() throws Exception {
        // when & then
        mockMvc.perform(get("/api/users"))
            .andExpect(status().isBadRequest());

        /*
         * 필수 파라미터 검증에서 실패했으므로
         * UserService는 호출되지 않아야 한다.
         */
        verifyNoInteractions(userService);
    }

    /**
     * roleEqual은 UserRole enum으로 변환
     *
     * <p>USER 또는 ADMIN이 아닌 값을 전달하면
     * Spring MVC의 쿼리 파라미터 변환 과정에서 요청이 거부되어야 한다.</p>
     */
    @Test
    @DisplayName("존재하지 않는 사용자 역할로 조회하면 400을 반환한다")
    void findUsers_fail_whenRoleIsInvalid() throws Exception {
        // when & then
        mockMvc.perform(
                get("/api/users")
                    .param("roleEqual", "MANAGER")
                    .param("limit", "20")
                    .param("sortDirection", "ASCENDING")
                    .param("sortBy", "createdAt")
            )
            .andExpect(status().isBadRequest());

        /*
         * roleEqual을 UserRole로 변환하는 과정에서 실패했으므로
         * UserService는 호출되지 않아야 한다.
         */
        verifyNoInteractions(userService);
    }

    /**
     * OpenAPI에서 허용하는 사용자 목록 정렬 기준은
     * name, email, createdAt, locked, role
     *
     * <p>허용 목록에 없는 updatedAt이 전달되면
     * UserListRequest의 @Pattern 검증에서 요청을 거부해야 한다.</p>
     */
    @Test
    @DisplayName("지원하지 않는 정렬 기준으로 조회하면 400을 반환한다")
    void findUsers_fail_whenSortByIsInvalid() throws Exception {
        // when & then
        mockMvc.perform(
                get("/api/users")
                    .param("limit", "20")
                    .param("sortDirection", "ASCENDING")
                    .param("sortBy", "updatedAt")
            )
            .andExpect(status().isBadRequest());

        /*
         * sortBy 검증에서 실패했으므로
         * UserService는 호출되지 않아야 한다.
         */
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("이메일이 비어 있으면 400을 반환하고 회원가입을 수행하지 않는다")
    void signUp_fail_whenEmailBlank() throws Exception {
        // given
        Map<String, String> request = Map.of(
            "name", "테스트 사용자",
            "email", "",
            "password", "passwordTest1!"
        );

        // when & then
        mockMvc.perform(post("/api/users")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("COMMON_400_1"))
            .andExpect(jsonPath("$.details.email").exists());

        // Controller의 입력 검증에서 막혀야 하므로 Service는 호출되면 안 됩니다.
        verifyNoInteractions(userService);
    }

    @Test
    // @DisplayName("인증된 사용자는 자신의 프로필을 조회할 수 있다")
    // void getMyProfile_success() throws Exception { users/me
    @DisplayName("사용자 ID로 사용자 상세 정보를 조회할 수 있다")
    void findUser_success() throws Exception {
        // given: JWT의 subject에서 복원됐다고 가정하는 사용자 UUID
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        Instant createdAt =
            Instant.parse("2026-07-31T03:00:00Z");

        /*      users/me
         * 실제 요청에서는 JwtAuthenticationFilter가 유효한 JWT를 검증한 뒤
         * UUID principal을 가진 Authentication을 SecurityContext에 저장
         *
         * 이 테스트는 Controller 단위 테스트이므로 JWT를 직접 발급·검증하지 않고
         * 필터 실행이 끝난 상태를 직접 구성
         *
        setAuthenticatedUser(userId);
        */

        UserDto response = new UserDto(
            userId,
            createdAt,
            "user@example.com",
            "테스트 사용자",
            "https://example.com/profile.png",
            UserRole.USER,
            false
        );

        /*      users/me
        when(userService.getMyProfile(userId))
            .thenReturn(response);
         */
        when(userService.findUser(userId))
            .thenReturn(response);

        // when & then
        // mockMvc.perform(get("/api/users/me"))
        mockMvc.perform(get("/api/users/{userId}", userId))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.id").value(userId.toString()))
            .andExpect(jsonPath("$.createdAt").value(createdAt.toString()))
            .andExpect(jsonPath("$.email").value("user@example.com"))
            .andExpect(jsonPath("$.name").value("테스트 사용자"))
            .andExpect(jsonPath("$.profileImageUrl")
                .value("https://example.com/profile.png"))
            .andExpect(jsonPath("$.role").value("USER"))
            .andExpect(jsonPath("$.locked").value(false))
            .andExpect(jsonPath("$.password").doesNotExist())
            .andExpect(jsonPath("$.passwordHash").doesNotExist());

        /* users/me
         * 요청자가 path나 body로 전달한 사용자 ID가 아니라
         * 인증 정보에 저장된 UUID가 Service로 전달되어야 함
         *
        verify(userService).getMyProfile(userId);
        */
        verify(userService).findUser(userId);
    }

    /*          users/me
    @Test
    @DisplayName("인증된 사용자에 해당하는 계정이 없으면 404를 반환한다")
    void getMyProfile_fail_whenUserDoesNotExist() throws Exception {

     */
    @Test
    @DisplayName("사용자 ID에 해당하는 계정이 없으면 404를 반환한다")
    void findUser_fail_whenUserDoesNotExist() throws Exception {
        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        // users/me
        // setAuthenticatedUser(userId);

        /*      users/me
         * JWT는 유효하지만 토큰 발급 이후 사용자가 삭제된 상황을 가정
         *
        when(userService.getMyProfile(userId))
            .thenThrow(
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
            );
        */
        when(userService.findUser(userId))
            .thenThrow(
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
            );

        // when & then
        // mockMvc.perform(get("/api/users/me"))
        mockMvc.perform(get("/api/users/{userId}", userId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("COMMON_404_1"));

        // verify(userService).getMyProfile(userId);
        verify(userService).findUser(userId);
    }

    /*          users/me
    /**
     * JWT 인증 필터가 처리한 것과 같은 UUID principal 인증 정보를 만든다.
     *
     * 현재 테스트 클래스는 addFilters=false 설정을 사용하므로
     * JwtAuthenticationFilter를 실행하지 않는다.
     * Controller의 인증 사용자 전달 동작만 독립적으로 검증하기 위해
     * SecurityContext를 직접 구성
     *
     * @param userId 현재 요청의 인증된 사용자 UUID

    private void setAuthenticatedUser(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                userId,
                null,
                List.of()
            )
        );
    }
    */

    // 프로필 수정 성공 테스트
    @Test
    @DisplayName("본인은 이름과 프로필 이미지를 수정할 수 있다")
    void updateUser_success_whenNameAndImageAreProvided()
        throws Exception {

        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        Instant createdAt =
            Instant.parse("2026-08-01T03:00:00Z");

        setAuthenticatedUser(userId);

        /*
         * multipart/form-data의 request 파트
         *
         * UserUpdateRequest는 JSON DTO이므로
         * Content-Type을 application/json으로 지정
         */
        MockMultipartFile requestPart = new MockMultipartFile(
            "request",
            "",
            "application/json",
            objectMapper.writeValueAsBytes(
                new UserUpdateRequest("변경된 사용자")
            )
        );

        /*
         * multipart/form-data의 image 파트
         *
         * 실제 이미지 파일 대신 테스트용 바이트 데이터를 사용
         */
        MockMultipartFile imagePart = new MockMultipartFile(
            "image",
            "profile.png",
            "image/png",
            new byte[]{1, 2, 3}
        );

        UserDto response = new UserDto(
            userId,
            createdAt,
            "user@example.com",
            "변경된 사용자",
            "https://placeholder.mopl.local/profile-images/new-profile.png",
            UserRole.USER,
            false
        );

        when(userService.updateUser(
            eq(userId),
            eq(userId),
            eq(new UserUpdateRequest("변경된 사용자")),
            any(MultipartFile.class)
        )).thenReturn(response);

        // when & then
        mockMvc.perform(
                multipart(
                    HttpMethod.PATCH,
                    "/api/users/{userId}",
                    userId
                )
                    .file(requestPart)
                    .file(imagePart)
            )
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.id").value(userId.toString()))
            .andExpect(jsonPath("$.createdAt")
                .value(createdAt.toString()))
            .andExpect(jsonPath("$.email")
                .value("user@example.com"))
            .andExpect(jsonPath("$.name")
                .value("변경된 사용자"))
            .andExpect(jsonPath("$.profileImageUrl")
                .value(
                    "https://placeholder.mopl.local/"
                        + "profile-images/new-profile.png"
                ))
            .andExpect(jsonPath("$.role").value("USER"))
            .andExpect(jsonPath("$.locked").value(false))
            .andExpect(jsonPath("$.password").doesNotExist())
            .andExpect(jsonPath("$.passwordHash").doesNotExist());

        /*
         * URL의 userId뿐만 아니라 JWT 인증 정보에서 가져온 UUID도
         * Service에 함께 전달됐는지 확인
         */
        verify(userService).updateUser(
            eq(userId),
            eq(userId),
            eq(new UserUpdateRequest("변경된 사용자")),
            any(MultipartFile.class)
        );
    }

    // 이미지 없이 이름만 수정하는 테스트
    @Test
    @DisplayName("프로필 이미지 없이 이름만 수정할 수 있다")
    void updateUser_success_whenImageIsNotProvided()
        throws Exception {

        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        Instant createdAt =
            Instant.parse("2026-08-01T03:00:00Z");

        setAuthenticatedUser(userId);

        MockMultipartFile requestPart = new MockMultipartFile(
            "request",
            "",
            "application/json",
            objectMapper.writeValueAsBytes(
                new UserUpdateRequest("변경된 사용자")
            )
        );

        UserDto response = new UserDto(
            userId,
            createdAt,
            "user@example.com",
            "변경된 사용자",
            "https://example.com/old-profile.png",
            UserRole.USER,
            false
        );

        when(userService.updateUser(
            userId,
            userId,
            new UserUpdateRequest("변경된 사용자"),
            null
        )).thenReturn(response);

        // when & then
        mockMvc.perform(
                multipart(
                    HttpMethod.PATCH,
                    "/api/users/{userId}",
                    userId
                )
                    .file(requestPart)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name")
                .value("변경된 사용자"))
            .andExpect(jsonPath("$.profileImageUrl")
                .value("https://example.com/old-profile.png"));

        /*
         * image 파트를 전달하지 않았으므로 Controller는
         * Service의 image 매개변수에 null을 전달해야 한다.
         */
        verify(userService).updateUser(
            userId,
            userId,
            new UserUpdateRequest("변경된 사용자"),
            null
        );
    }

    // 잘못된 이름 검증 테스트
    @Test
    @DisplayName("수정할 이름이 공백이면 400을 반환한다")
    void updateUser_fail_whenNameIsBlank()
        throws Exception {

        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        setAuthenticatedUser(userId);

        MockMultipartFile requestPart = new MockMultipartFile(
            "request",
            "",
            "application/json",
            objectMapper.writeValueAsBytes(
                new UserUpdateRequest("   ")
            )
        );

        // when & then
        mockMvc.perform(
                multipart(
                    HttpMethod.PATCH,
                    "/api/users/{userId}",
                    userId
                )
                    .file(requestPart)
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode")
                .value("COMMON_400_1"))
            .andExpect(jsonPath("$.details.name").exists());

        /*
         * Controller 입력 검증에서 실패했으므로
         * Service는 호출되면 안 된다.
         */
        verifyNoInteractions(userService);
    }

    /**
     * 올바른 새 비밀번호가 전달되면 비밀번호 변경에 성공하고
     * 응답 본문 없이 204 No Content를 반환하는지 검증
     *
     * Controller가 다음 값을 Service에 정확히 전달하는지도 확인
     *
     * 1. JWT 인증 정보에 저장된 사용자 UUID
     * 2. URL 경로로 전달된 변경 대상 사용자 UUID
     * 3. JSON 요청 본문에서 변환된 ChangePasswordRequest
     */
    @Test
    @DisplayName("본인은 자신의 비밀번호를 변경할 수 있다")
    void changePassword_success() throws Exception {
        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        /*
         * 실제 요청에서는 JwtAuthenticationFilter가 JWT의 subject에서
         * 사용자 UUID를 가져와 Authentication principal에 저장
         *
         * Controller 단위 테스트에서는 Security Filter를 비활성화했으므로
         * 동일한 형태의 인증 정보를 SecurityContext에 직접 설정
         */
        setAuthenticatedUser(userId);

        ChangePasswordRequest request =
            new ChangePasswordRequest("newPassword1!");

        // when & then
        mockMvc.perform(
                patch(
                    "/api/users/{userId}/password",
                    userId
                )
                    .contentType("application/json")
                    .content(objectMapper.writeValueAsString(request))
            )
            .andExpect(status().isNoContent())
            /*
             * 204 No Content 응답에는 JSON 응답 본문이 없어야 한다.
             *
             * 비밀번호나 비밀번호 해시는 민감 정보이므로
             * 변경 결과를 응답으로 반환하지 않는다.
             */
            .andExpect(content().string(""));

        /*
         * 인증 사용자 UUID, URL의 대상 사용자 UUID,
         * 요청 본문이 Service에 정확하게 전달되었는지 검증
         */
        verify(userService).changePassword(
            userId,
            userId,
            new ChangePasswordRequest("newPassword1!")
        );
    }

    /**
     * 새 비밀번호가 비어 있으면 DTO의 Bean Validation에서 요청을 거절하고
     * UserService를 호출하지 않는지 검증
     *
     * @Valid 검증은 Controller 메서드가 실행되기 전에 수행되므로
     * 잘못된 요청이 비즈니스 로직까지 전달되면 안된다.
     */
    @Test
    @DisplayName("새 비밀번호가 비어 있으면 400을 반환한다")
    void changePassword_fail_whenPasswordIsBlank() throws Exception {
        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        setAuthenticatedUser(userId);

        ChangePasswordRequest request =
            new ChangePasswordRequest("");

        // when & then
        mockMvc.perform(
                patch(
                    "/api/users/{userId}/password",
                    userId
                )
                    .contentType("application/json")
                    .content(objectMapper.writeValueAsString(request))
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode")
                .value("COMMON_400_1"))
            .andExpect(jsonPath("$.details.password").exists());

        /*
         * Controller 입력 검증 단계에서 실패했으므로
         * 비밀번호 암호화와 DB 조회를 담당하는 Service는 호출되면 안된다.
         */
        verifyNoInteractions(userService);
    }

    /**
     * 올바른 사용자 권한 변경 요청이 전달되면
     * Service를 호출하고 204 No Content를 반환하는지 검증
     */
    @Test
    @DisplayName("사용자 권한 변경 요청 시 서비스를 호출하고 204를 반환한다")
    void updateRole_success() throws Exception {
        // given
        UUID targetUserId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        UserRoleUpdateRequest request =
            new UserRoleUpdateRequest(UserRole.ADMIN);

        // when & then
        /*
         * 이 테스트 클래스는 Security Filter를 비활성화한 Controller 단위 테스트
         *
         * 관리자 권한 검증은 SecurityAccessPolicyTest에서 별도로 확인하고,
         * 여기서는 정상 요청의 역직렬화, Service 전달,
         * 204 응답만 검증
         */
        mockMvc.perform(
                patch(
                    "/api/users/{userId}/role",
                    targetUserId
                )
                    .contentType("application/json")
                    .content(objectMapper.writeValueAsString(request))
            )
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        verify(userService).updateRole(
            targetUserId,
            new UserRoleUpdateRequest(UserRole.ADMIN)
        );
    }

    /**
     * role 값이 null이면 DTO Bean Validation에서 요청을 거절하고
     * Service까지 호출되지 않는지 검증
     */
    @Test
    @DisplayName("사용자 권한이 누락되면 400을 반환한다")
    void updateRole_fail_whenRoleIsNull() throws Exception {
        // given
        UUID targetUserId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        UserRoleUpdateRequest request =
            new UserRoleUpdateRequest(null);

        // when & then
        mockMvc.perform(
                patch(
                    "/api/users/{userId}/role",
                    targetUserId
                )
                    .contentType("application/json")
                    .content(objectMapper.writeValueAsString(request))
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode")
                .value("COMMON_400_1"))
            .andExpect(jsonPath("$.details.role").exists());

        /*
         * @Valid 검증이 Controller 실행 전에 실패하므로
         * Service가 호출되면 안된다.
         */
        verifyNoInteractions(userService);
    }

    /**
     * UserRole enum에 존재하지 않는 문자열이 전달되면
     * JSON 역직렬화 단계에서 400을 반환하는지 검증
     */
    @Test
    @DisplayName("지원하지 않는 사용자 권한이면 400을 반환한다")
    void updateRole_fail_whenRoleIsInvalid() throws Exception {
        // given
        UUID targetUserId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        /*
         * UserRoleUpdateRequest 생성자로는 존재하지 않는 enum 값을
         * 만들 수 없으므로 실제 JSON 문자열을 직접 전달
         */
        String requestBody = """
            {
              "role": "MANAGER"
            }
            """;

        // when & then
        mockMvc.perform(
                patch(
                    "/api/users/{userId}/role",
                    targetUserId
                )
                    .contentType("application/json")
                    .content(requestBody)
            )
            .andExpect(status().isBadRequest());

        /*
         * JSON을 UserRoleUpdateRequest로 변환하는 단계에서 실패하므로
         * Controller 메서드와 Service는 실행되지 않는다.
         */
        verifyNoInteractions(userService);
    }

    /**
     * ROLE_ADMIN 권한을 가진 사용자가 계정 잠금 상태를 변경하면
     * Service에 대상 사용자와 요청이 전달되고 204를 반환하는지 검증
     */
    @Test
    @DisplayName("계정 잠금 상태 변경 요청 시 서비스를 호출하고 204를 반환한다")
    void updateLocked_success() throws Exception {
        // given
        UUID targetUserId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        UserLockUpdateRequest request =
            new UserLockUpdateRequest(true);

        // when & then
        /*
         * 이 테스트는 Security Filter를 비활성화한 Controller 단위 테스트
         * 관리자 권한 검증은 SecurityAccessPolicyTest에서 별도로 확인하고
         * 여기서는 올바른 요청이 서비스에 전달되고 204 응답이 반환되는지만 검증
         */
        mockMvc.perform(
                patch(
                    "/api/users/{userId}/locked",
                    targetUserId
                )
                    .contentType("application/json")
                    .content(objectMapper.writeValueAsString(request))
            )
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        verify(userService).updateLocked(
            targetUserId,
            new UserLockUpdateRequest(true)
        );
    }

    /**
     * locked 값이 null이면 DTO Bean Validation에서 요청을 거절하고
     * Controller 메서드와 Service 호출까지 진행하지 않는지 검증
     */
    @Test
    @DisplayName("계정 잠금 상태가 누락되면 400을 반환한다")
    void updateLocked_fail_whenLockedIsNull() throws Exception {
        // given
        UUID targetUserId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        UserLockUpdateRequest request =
            new UserLockUpdateRequest(null);

        // when & then
        mockMvc.perform(
                patch(
                    "/api/users/{userId}/locked",
                    targetUserId
                )
                    .contentType("application/json")
                    .content(objectMapper.writeValueAsString(request))
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode")
                .value("COMMON_400_1"))
            .andExpect(jsonPath("$.details.locked").exists());

        /*
         * @Valid 검증이 Controller 메서드 실행 전에 실패하므로
         * Service는 호출되지 않는다.
         */
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("본인의 OAuth 연결 계정 목록을 조회하면 200과 공개 정보를 반환한다")
    void getLinkedOAuthAccounts_success()
        throws Exception {

        UUID userId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        Instant googleConnectedAt =
            Instant.parse("2026-08-01T01:00:00Z");

        Instant naverConnectedAt =
            Instant.parse("2026-08-02T01:00:00Z");

        setAuthenticatedUser(userId);

        when(
            oauthAccountManagementService
                .getLinkedAccounts(
                    userId,
                    userId
                )
        ).thenReturn(
            List.of(
                new OAuthAccountDto(
                    OAuthProvider.GOOGLE,
                    googleConnectedAt
                ),
                new OAuthAccountDto(
                    OAuthProvider.NAVER,
                    naverConnectedAt
                )
            )
        );

        mockMvc.perform(
                get(
                    "/api/users/{userId}/oauth-accounts",
                    userId
                )
            )
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$[0].provider")
                .value("GOOGLE"))
            .andExpect(jsonPath("$[0].connectedAt")
                .value(googleConnectedAt.toString()))
            .andExpect(jsonPath("$[1].provider")
                .value("NAVER"))
            .andExpect(jsonPath("$[1].connectedAt")
                .value(naverConnectedAt.toString()))
            .andExpect(jsonPath("$[0].providerUserId")
                .doesNotExist())
            .andExpect(jsonPath("$[0].accessToken")
                .doesNotExist())
            .andExpect(jsonPath("$[0].refreshToken")
                .doesNotExist());

        verify(oauthAccountManagementService)
            .getLinkedAccounts(
                userId,
                userId
            );
    }

    @Test
    @DisplayName("OAuth 계정 연결 시작 시 인증 경로를 반환한다")
    void startOAuthAccountLink_success()
        throws Exception {
        // given
        UUID userId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        setAuthenticatedUser(userId);

        // when & then
        mockMvc.perform(
                post(
                    "/api/users/{userId}/oauth-accounts/{provider}/link",
                    userId,
                    OAuthProvider.GOOGLE
                )
            )
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.authorizationPath")
                    .value(
                        "/oauth2/authorization/google"
                    )
            );

        verify(oauthAccountManagementService)
            .validateLinkStart(
                userId,
                userId,
                OAuthProvider.GOOGLE
            );

        verify(oauthLinkIntentSessionStore)
            .save(
                any(HttpServletRequest.class),
                eq(userId),
                eq(OAuthProvider.GOOGLE)
            );
    }

    @Test
    @DisplayName("연결 시작 검증에 실패하면 세션에 연결 의도를 저장하지 않는다")
    void startOAuthAccountLink_fail_doesNotStoreIntent()
        throws Exception {
        // given
        UUID userId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        setAuthenticatedUser(userId);

        doThrow(
            new BusinessException(
                ErrorCode.OAUTH_ACCOUNT_CONFLICT
            )
        ).when(
            oauthAccountManagementService
        ).validateLinkStart(
            userId,
            userId,
            OAuthProvider.KAKAO
        );

        // when & then
        mockMvc.perform(
                post(
                    "/api/users/{userId}/oauth-accounts/{provider}/link",
                    userId,
                    OAuthProvider.KAKAO
                )
            )
            .andExpect(status().isConflict());

        verifyNoInteractions(
            oauthLinkIntentSessionStore
        );
    }

    @Test
    @DisplayName("본인의 OAuth 연결 계정을 해제하면 204를 반환한다")
    void unlinkOAuthAccount_success()
        throws Exception {
        // given
        UUID userId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        setAuthenticatedUser(userId);

        // when & then
        mockMvc.perform(
                delete(
                    "/api/users/{userId}/oauth-accounts/{provider}",
                    userId,
                    OAuthProvider.GOOGLE
                )
            )
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        verify(oauthAccountManagementService)
            .unlinkAccount(
                userId,
                userId,
                OAuthProvider.GOOGLE
            );
    }

    @Test
    @DisplayName("지원하지 않는 OAuth Provider이면 400을 반환한다")
    void unlinkOAuthAccount_fail_whenProviderIsInvalid()
        throws Exception {
        // given
        UUID userId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        setAuthenticatedUser(userId);

        // when & then
        mockMvc.perform(
                delete(
                    "/api/users/{userId}/oauth-accounts/{provider}",
                    userId,
                    "FACEBOOK"
                )
            )
            .andExpect(status().isBadRequest());

        /*
         * PathVariable을 OAuthProvider로 변환하는 과정에서 실패하므로
         * Controller 메서드와 Service는 실행되지 않는다.
         */
        verifyNoInteractions(
            oauthAccountManagementService
        );
    }

    /**
     * 인증 사용자 설정 메서드
     *
     * JWT 인증 필터가 성공적으로 인증을 처리한 상태를 구성
     *
     * 실제 요청에서는 JwtAuthenticationFilter가 액세스 토큰의
     * subject에서 사용자 UUID를 추출해 Authentication principal에 저장
     *
     * 이 테스트는 Controller 단위 테스트이고 Security Filter가 비활성화되어
     * 있으므로 같은 형태의 인증 정보를 직접 SecurityContext에 설정
     *
     * @param userId 현재 인증된 사용자의 UUID
     */
    private void setAuthenticatedUser(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                userId,
                null,
                List.of()
            )
        );
    }
}

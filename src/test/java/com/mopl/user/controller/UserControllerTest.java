package com.mopl.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.dto.UserCreateRequest;
import com.mopl.user.dto.UserDto;
import com.mopl.user.dto.UserUpdateRequest;
import com.mopl.user.entity.UserRole;
import com.mopl.user.service.UserService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.List;
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

package com.mopl.user.service;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.util.CursorUtils;
import com.mopl.user.dto.UserListRequest;
import com.mopl.user.dto.UserCreateRequest;
import com.mopl.user.dto.UserDto;
import com.mopl.user.dto.UserUpdateRequest;
import com.mopl.user.dto.UserLockUpdateRequest;
import com.mopl.user.dto.UserRoleUpdateRequest;
import com.mopl.user.dto.ChangePasswordRequest;
import com.mopl.user.storage.ProfileImageStorage;
import com.mopl.user.storage.RefreshTokenStore;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import java.time.DateTimeException;
import java.util.Locale;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

// 사용자 회원가입과 관련된 비즈니스 규칙 처리
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private static final Set<String> USER_SORT_FIELDS = Set.of(
        "name",
        "email",
        "createdAt",
        "locked",
        "role"
    );

    private static final Set<String> SORT_DIRECTIONS = Set.of(
        "ASCENDING",
        "DESCENDING"
    );

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ProfileImageStorage profileImageStorage;
    private final RefreshTokenStore refreshTokenStore;

    /**
     * 이메일·비밀번호 기반 회원가입을 처리
     * <p>
     * 이메일은 공백을 제거하고 소문자로 정규화 비밀번호는 원문 대신 PasswordEncoder로 인코딩한 해시만 저장
     */
    @Transactional
    public UserDto signUp(UserCreateRequest request) {
        String normalizedEmail = normalizeEmail(request.email());

        // 에러코드 공통영역 작업중. 추후 에러코드 확인 및 수정 예정
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException(ErrorCode.EMAIL_DUPLICATE);
        }

        String passwordHash = passwordEncoder.encode(request.password());

        User user = User.builder()
            .email(normalizedEmail)
            .passwordHash(passwordHash)
            .name(request.name())
            .role(UserRole.USER)
            .locked(false)
            .build();

        User savedUser;

        try {
            // INSERT SQL을 즉시 실행해 동시 가입 시 DB 유니크 제약 오류를 여기서 처리
            savedUser = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.EMAIL_DUPLICATE);
        }

        return UserDto.from(savedUser);
    }

    /**
     * 이메일을 계정 식별에 사용할 수 있는 형태로 통일
     * <p>
     * 사용자는 대문자, 앞뒤 공백 포함하여 입력(복붙 시 공백 들어갈 수 있을 때)할 수 있지만 저장과 조회는 항상 같은 정규화 규칙 적용
     */
    private String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }


    /**
     * 사용자 UUID로 사용자 상세 정보를 조회
     * <p>
     * Swagger에 정의된 GET /api/users/{userId} 요청에서 전달받은 사용자 UUID를 기준으로 데이터베이스의 사용자 정보를 조회
     * <p>
     * 사용자 계정이 존재하지 않으면 RESOURCE_NOT_FOUND 예외를 발생
     *
     * @param userId 조회할 사용자 UUID
     * @return 비밀번호 해시를 제외한 사용자 상세 정보
     * @throws BusinessException 사용자가 존재하지 않는 경우
     */
    public UserDto findUser(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
            );

        return UserDto.from(user);
    }

    /*
    // GET /api/users/me 부분. 추후 선택기능 개발 과정에서 살릴 부분임.

    /**
     * 인증된 사용자의 UUID로 자신의 프로필을 조회
     *
     * JWT 인증이 완료되면 Authentication principal에 사용자 UUID가 저장
     * Controller는 해당 UUID를 이 메서드에 전달하고,
     * Service는 데이터베이스에서 현재 사용자 정보를 조회한 뒤 UserDto로 변환
     *
     * JWT가 발급된 이후 사용자가 탈퇴하거나 삭제되었을 수도 있으므로,
     * 토큰에 UUID가 존재하더라도 데이터베이스 조회 결과가 없으면
     * RESOURCE_NOT_FOUND 예외를 발생
     *
     * @param userId JWT 인증 정보에서 가져온 사용자 UUID
     * @return 비밀번호 해시를 제외한 자신의 프로필 정보
     * @throws BusinessException 사용자 계정이 존재하지 않는 경우

    public UserDto getMyProfile(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
            );

        return UserDto.from(user);
    }
*/

    /**
     * 관리자가 사용자 목록을 커서 페이지네이션으로 조회
     *
     * Repository는 요청 limit보다 한 건 더 조회하고,
     * Service는 추가 조회된 한 건을 이용해 다음 페이지 존재 여부를 판단
     *
     * 실제 응답에는 요청 limit만큼만 포함하고, 다음 페이지가 있다면
     * 마지막 응답 사용자의 정렬 값과 UUID를 다음 커서로 반환
     *
     * @param request 검색·필터·커서·정렬 조건
     * @return 사용자 목록과 다음 페이지 정보를 포함한 커서 응답
     */
    public CursorResponse<UserDto> findUsers(
        UserListRequest request
    ) {
        /*
         * Service를 Controller 외부에서 호출하더라도 잘못된 값이
         * Repository까지 전달되지 않도록 비즈니스 경계에서 다시 검증
         */
        validateUserListRequest(request);

        /*
         * Repository는 hasNext 확인을 위해 최대 limit + 1건을 반환
         */
        List<User> rows = userRepository.findUsers(request);

        boolean hasNext = rows.size() > request.limit();

        /*
         * 추가 조회된 한 건은 응답 데이터에 포함하지 않는다.
         */
        List<User> page = hasNext
            ? rows.subList(0, request.limit())
            : rows;

        String nextCursor = null;
        UUID nextIdAfter = null;

        /*
         * 다음 페이지가 존재할 때 현재 응답의 마지막 사용자를
         * 다음 요청의 커서 기준으로 사용
         */
        if (hasNext && !page.isEmpty()) {
            User lastUser = page.get(page.size() - 1);

            nextCursor = buildNextUserCursor(
                lastUser,
                request.sortBy()
            );

            nextIdAfter = lastUser.getId();
        }

        List<UserDto> data = page.stream()
            .map(UserDto::from)
            .toList();

        /*
         * totalCount에는 커서 이후의 개수가 아니라
         * 필터 조건에 해당하는 전체 사용자 수를 반환
         */
        long totalCount = userRepository.countUsers(request);

        return CursorResponse.of(
            data,
            nextCursor,
            nextIdAfter,
            hasNext,
            totalCount,
            request.sortBy(),
            request.sortDirection()
        );
    }

    /**
     * 사용자의 이름과 프로필 이미지를 변경
     * <p>
     * Swagger 계약의 PATCH /api/users/{userId} 요청을 처리
     * 프로필은 본인만 수정할 수 있으므로 인증된 사용자 UUID와 URL 경로로 전달된 수정 대상 사용자 UUID를 비교
     * <p>
     * 이미지 파일이 전달된 경우 ProfileImageStorage에 업로드하고, 반환된 이미지 URL을 사용자 엔티티에 반영
     * image 파트가 없거나 빈 파일이면 기존 프로필 이미지 URL을 유지
     * 현재 계약은 프로필 이미지 등록과 교체만 지원하며 이미지 삭제는 지원하지 않음
     *
     * [동작 순서]
     * 1. JWT 인증 사용자 UUID가 있는지 확인
     * 2. 인증 사용자와 수정 대상 userId가 같은지 확인
     * 3. users 테이블에서 사용자 조회
     * 4. 이미지가 있다면 ProfileImageStorage에 업로드
     * 5. 이름과 이미지 URL을 User 엔티티에 반영
     * 6. UserDto로 변환해 반환
     * 7. 트랜잭션 종료 시 JPA 변경 감지로 UPDATE 실행
     *
     *
     *
     * @param authenticatedUserId JWT 인증 정보에서 가져온 사용자 UUID
     * @param userId              URL 경로로 전달된 수정 대상 사용자 UUID
     * @param request             변경할 프로필 정보
     * @param image               새 프로필 이미지 파일, 없으면 null
     * @return 수정된 사용자 정보
     * @throws BusinessException 인증 정보가 없는 경우
     * @throws BusinessException 다른 사용자의 프로필을 수정하려는 경우
     * @throws BusinessException 수정할 사용자가 존재하지 않는 경우
     */
    @Transactional
    public UserDto updateUser(
        UUID authenticatedUserId,
        UUID userId,
        UserUpdateRequest request,
        MultipartFile image
    ) {
        // 인증 정보가 없다면 프로필 수정 작업을 수행할 수 없다.
        if (authenticatedUserId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        /*
         * 인증된 사용자와 수정 대상 사용자가 다르면 다른 사람의 프로필을
         * 수정하려는 요청이므로 권한 없음 오류를 발생
         *
         * 사용자 조회보다 먼저 검사하여 다른 사용자의 존재 여부가
         * 불필요하게 노출되지 않도록 한다.
         */
        if (!authenticatedUserId.equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        User user = userRepository.findById(userId)
            .orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
            );

        /*
         * image가 null이면 multipart 요청에 이미지 파트가 없는 경우
         * 빈 파일이 전달된 경우도 새 이미지로 처리하지 않음
         */
        String profileImageUrl = null;

        if (image != null && !image.isEmpty()) {
            validateProfileImage(image);
            profileImageUrl = profileImageStorage.upload(image);
        }

        /*
         * 엔티티가 영속 상태이므로 updateProfile()로 필드가 변경되면
         * 트랜잭션이 끝날 때 JPA의 변경 감지가 UPDATE SQL을 실행
         * 따라서 userRepository.save(user)를 다시 호출할 필요가 없다.
         */
        user.updateProfile(
            request.name(),
            profileImageUrl
        );

        return UserDto.from(user);
    }

    /**
     * 인증된 사용자의 비밀번호를 새로운 비밀번호로 변경
     *
     * Swagger 계약의 PATCH /api/users/{userId}/password 요청을 처리
     *
     * 비밀번호는 본인만 변경할 수 있으므로 JWT 인증 정보에서 가져온
     * 사용자 UUID와 URL 경로의 수정 대상 userId를 비교
     *
     * 요청으로 전달된 비밀번호 원문은 데이터베이스에 저장하지 않고,
     * PasswordEncoder로 인코딩한 해시만 User 엔티티에 반영
     *
     * 현재 Swagger 계약에는 기존 비밀번호가 포함되어 있지 않으므로
     * 이번 기본 구현에서는 기존 비밀번호를 별도로 검증하지 않는다.
     *
     * @param authenticatedUserId JWT 인증 정보에서 가져온 사용자 UUID
     * @param userId URL 경로로 전달된 비밀번호 변경 대상 사용자 UUID
     * @param request 새 비밀번호를 담은 요청
     * @throws BusinessException 인증 정보가 없는 경우
     * @throws BusinessException 다른 사용자의 비밀번호를 변경하려는 경우
     * @throws BusinessException 변경할 사용자가 존재하지 않는 경우
     */
    @Transactional
    public void changePassword(
        UUID authenticatedUserId,
        UUID userId,
        ChangePasswordRequest request
    ) {
        /*
         * 인증 정보가 없다면 비밀번호 변경을 수행할 수 없다.
         *
         * 전역 SecurityConfig에서도 인증을 검사하지만,
         * Service에서도 비즈니스 규칙을 보장하도록 방어
         */
        if (authenticatedUserId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        /*
         * JWT 사용자 UUID와 URL의 대상 사용자 UUID가 다르면
         * 다른 사용자의 비밀번호를 변경하려는 요청
         *
         * 사용자 조회보다 먼저 확인하여 공격자가 임의의 UUID를 넣어
         * 다른 계정의 존재 여부를 확인하지 못하게 한다.
         */
        if (!authenticatedUserId.equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        /*
         * 본인 여부를 확인한 다음 데이터베이스에서 사용자를 조회
         *
         * JWT가 발급된 이후 계정이 삭제될 수 있으므로
         * 유효한 토큰이 있더라도 사용자가 항상 존재한다고 가정하지 않는다.
         */
        User user = userRepository.findById(userId)
            .orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
            );

        /*
         * 비밀번호 원문은 DB에 저장하거나 엔티티에 전달하지 않는다.
         *
         * 회원가입과 로그인에 사용하는 동일한 PasswordEncoder Bean으로
         * 새 비밀번호를 인코딩
         */
        String encodedPassword =
            passwordEncoder.encode(request.password());

        /*
         * User 엔티티에는 PasswordEncoder의 결과인 해시만 전달
         */
        user.changePassword(encodedPassword);

        /*
         * 비밀번호가 변경되면 기존 비밀번호로 생성됐던 모든 로그인 세션을
         * 더 이상 신뢰할 수 없으므로 사용자의 Refresh Token Family를
         * 전부 폐기
         *
         * 폐기할 세션이 없는 경우에는 0을 반환하지만 비밀번호 변경은
         * 정상적으로 계속 처리
         *
         * Redis 처리 중 예외가 발생하면 예외를 숨기지 않는다.
         * 이 메서드는 @Transactional 범위에 있으므로 런타임 예외가
         * 전파되면 데이터베이스의 비밀번호 변경도 롤백
         */
        refreshTokenStore.revokeAllByUserId(
            userId
        );
    }

    /**
     * 관리자의 요청에 따라 사용자의 권한을 변경
     *
     * 관리자 권한 검증은 SecurityFilterChain에서 수행
     * Service는 권한 변경 대상 사용자 조회와 상태 변경을 담당
     *
     * 대상 사용자가 존재하지 않으면 RESOURCE_NOT_FOUND를 발생
     *
     * 조회한 User는 현재 트랜잭션 안에서 영속 상태이므로
     * updateRole()로 상태를 변경하면 트랜잭션 종료 시
     * JPA 변경 감지를 통해 UPDATE SQL이 실행
     *
     * 따라서 userRepository.save(user)를 다시 호출할 필요가 없다.
     *
     * @param userId 권한을 변경할 대상 사용자의 UUID
     * @param request 새로 적용할 사용자 권한이 담긴 요청
     * @throws BusinessException 대상 사용자가 존재하지 않는 경우
     */
    @Transactional
    public void updateRole(
        UUID userId,
        UserRoleUpdateRequest request
    ) {
        /*
         * 변경 대상 사용자를 UUID로 한 번 조회
         *
         * 존재하지 않는 사용자의 권한은 변경할 수 없으므로
         * 공통 RESOURCE_NOT_FOUND 예외를 발생
         */
        User user = userRepository.findById(userId)
            .orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
            );

        /*
         * 현재 권한과 요청 권한이 같다면 실제 보안 상태 변화가 없다.
         *
         * 동일한 권한을 다시 요청했다는 이유만으로 사용자의 모든 기기에서
         * 로그아웃시키지 않도록 상태 변경과 세션 폐기를 생략한다.
         */
        if (user.getRole() == request.role()) {
            return;
        }

        /*
         * 사용자 권한을 변경
         *
         * 기존 Access Token에는 변경 전 role이 들어 있으며,
         * 기존 Refresh Token으로도 이전 인증 상태를 이어갈 수 있으므로
         * 권한 변경과 함께 모든 Refresh Token Family를 폐기
         */
        user.updateRole(request.role());

        /*
         * 변경 전 권한을 기준으로 만들어진 모든 로그인 세션을 폐기
         *
         * Redis 폐기 중 런타임 예외가 발생하면 예외를 숨기지 않는다.
         * updateRole()은 @Transactional 메서드이므로 데이터베이스의
         * 권한 변경도 커밋되지 않는다.
         */
        refreshTokenStore.revokeAllByUserId(
            userId
        );

        /*
         * user는 현재 트랜잭션에서 조회한 영속 엔티티
         *
         * 트랜잭션이 정상 종료되면 JPA 변경 감지가
         * role 필드 변경을 확인하여 UPDATE SQL을 실행
         *
         * userRepository.save(user)는 호출하지 않는다.
         */
    }

    /**
     * 관리자의 요청에 따라 사용자 계정의 잠금 상태를 변경
     *
     * 관리자 권한 검증은 HTTP 인증 정보를 사용할 수 있는 Controller에서
     * 먼저 수행하고, Service는 대상 사용자 조회와 상태 변경을 담당
     *
     * 대상 사용자가 존재하지 않으면 RESOURCE_NOT_FOUND를 발생시킴
     * 조회된 User는 영속 상태이므로 updateLocked() 호출 후 별도의
     * save() 없이 JPA 변경 감지를 통해 데이터베이스에 반영
     *
     * @param userId 잠금 상태를 변경할 대상 사용자의 UUID
     * @param request 새 잠금 상태가 담긴 요청
     * @throws BusinessException 대상 사용자가 존재하지 않는 경우
     */
    @Transactional
    public void updateLocked(
        UUID userId,
        UserLockUpdateRequest request
    ) {
        /*
         * 대상 사용자는 한 번만 조회
         *
         * 존재하지 않는 사용자는 잠금 상태를 변경할 수 없으므로
         * RESOURCE_NOT_FOUND를 발생시킴
         */
        User user = userRepository.findById(userId)
            .orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
            );

        /*
         * 요청된 잠금 상태를 사용자 엔티티에 반영
         */
        user.updateLocked(request.locked());

        /*
         * 계정을 잠그는 요청이라면 현재 사용 중인 모든 기기의
         * Refresh Token Family를 폐기
         *
         * 이미 잠긴 계정에 다시 locked=true가 전달된 경우에도
         * 혹시 남아 있는 세션을 제거할 수 있도록 폐기를 수행
         *
         * 잠금을 해제하는 locked=false 요청에서는 세션을 복원하거나
         * 폐기하지 않는다. 잠금 해제 후 사용자가 다시 로그인
         */
        if (request.locked()) {
            refreshTokenStore.revokeAllByUserId(
                userId
            );
        }

        /*
         * user는 현재 트랜잭션 안에서 조회된 영속 엔티티
         *
         * 트랜잭션 종료 시 JPA 변경 감지가 locked 필드 변경을 확인하여
         * UPDATE SQL을 실행하므로 userRepository.save(user)를
         * 다시 호출할 필요가 없음
         */
    }

    /**
     * 사용자 목록 요청의 필수값과 커서 형식을 검증
     *
     * Controller의 Bean Validation만 의존하지 않고 Service에서도 검증하여
     * 내부 호출에서도 동일한 비즈니스 규칙을 보장
     */
    private void validateUserListRequest(
        UserListRequest request
    ) {
        if (request == null
            || request.limit() == null
            || request.limit() < 1
            || request.limit() > 100
            || !USER_SORT_FIELDS.contains(request.sortBy())
            || !SORT_DIRECTIONS.contains(request.sortDirection())) {

            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        boolean hasCursor = request.cursor() != null;
        boolean hasIdAfter = request.idAfter() != null;

        /*
         * 주 커서와 보조 커서는 하나의 페이지 위치를 나타내므로
         * 반드시 함께 전달되거나 함께 생략되어야 한다.
         */
        if (hasCursor != hasIdAfter) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        if (!hasCursor) {
            return;
        }

        /*
         * Repository 호출 전에 Base64와 실제 필드 타입을 검증
         *
         * 이렇게 해야 잘못된 외부 입력이 Spring의 Repository 예외로
         * 변환되지 않고 일관된 INVALID_INPUT 응답으로 처리
         */
        try {
            switch (request.sortBy()) {
                case "name", "email" ->
                    CursorUtils.decode(request.cursor());

                case "createdAt" ->
                    CursorUtils.decodeAsInstant(request.cursor());

                case "locked" ->
                    validateBooleanCursor(request.cursor());

                case "role" ->
                    UserRole.valueOf(
                        CursorUtils.decode(request.cursor())
                    );

                default ->
                    throw new IllegalArgumentException(
                        "지원하지 않는 사용자 정렬 기준입니다."
                    );
            }
        } catch (
            IllegalArgumentException | DateTimeException exception
        ) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    /**
     * 잠금 상태 커서가 true 또는 false인지 검증
     *
     * Boolean.valueOf()는 잘못된 문자열도 false로 바꾸므로 사용하지 않는다.
     */
    private void validateBooleanCursor(String cursor) {
        String decoded = CursorUtils.decode(cursor);

        if (!"true".equals(decoded)
            && !"false".equals(decoded)) {

            throw new IllegalArgumentException(
                "잠금 상태 커서 형식이 올바르지 않습니다."
            );
        }
    }

    /**
     * 현재 페이지 마지막 사용자의 정렬 값을 다음 커서로 변환
     */
    private String buildNextUserCursor(
        User user,
        String sortBy
    ) {
        return switch (sortBy) {
            case "name" ->
                CursorUtils.encode(user.getName());

            case "email" ->
                CursorUtils.encode(user.getEmail());

            case "createdAt" ->
                CursorUtils.encodeInstant(user.getCreatedAt());

            case "locked" ->
                CursorUtils.encode(
                    Boolean.toString(user.isLocked())
                );

            case "role" ->
                CursorUtils.encode(user.getRole().name());

            default ->
                throw new BusinessException(
                    ErrorCode.INVALID_INPUT
                );
        };
    }

    /**
     * 업로드 파일의 Content-Type이 이미지 형식인지 검증
     *
     * multipart 요청의 image 파트에는 이미지 파일만 전달되어야 하므로,
     * Content-Type이 없거나 image 타입이 아니면 잘못된 입력으로 처리
     *
     * 이 검증은 클라이언트가 전달한 MIME 타입을 기준으로 하는 기본 검증
     * 실제 파일 내용과 확장자가 일치하는지는 실제 이미지 저장소 연동 시
     * 파일 시그니처 검증 등을 통해 추가로 확인할 수 있다.
     *
     * @param image 검증할 프로필 이미지 파일
     * @throws BusinessException MIME 타입이 없거나 이미지가 아닌 경우
     */
    private void validateProfileImage(MultipartFile image) {
        String contentType = image.getContentType();

        if (contentType == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        try {
            MediaType mediaType =
                MediaType.parseMediaType(contentType);

            if (!"image".equalsIgnoreCase(mediaType.getType())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
        } catch (InvalidMediaTypeException exception) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }
}

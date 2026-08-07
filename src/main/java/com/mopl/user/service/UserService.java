package com.mopl.user.service;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.dto.UserCreateRequest;
import com.mopl.user.dto.UserDto;
import com.mopl.user.dto.UserUpdateRequest;
import com.mopl.user.dto.UserLockUpdateRequest;
import com.mopl.user.dto.UserRoleUpdateRequest;
import com.mopl.user.dto.ChangePasswordRequest;
import com.mopl.user.storage.ProfileImageStorage;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import java.util.Locale;
import java.util.UUID;
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

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ProfileImageStorage profileImageStorage;

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
         * user는 현재 트랜잭션에서 조회한 영속 엔티티
         *
         * 트랜잭션이 정상적으로 종료되면 JPA 변경 감지가 password_hash의
         * 변경을 확인해 UPDATE SQL을 실행하므로 save() 호출은 필요하지 않다.
         */
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
         * request.role()은 Controller의 @Valid와
         * UserRoleUpdateRequest의 @NotNull 검증을 통과한 값
         *
         * UserRole enum 타입이므로 USER 또는 ADMIN 중 하나만 전달
         */
        user.updateRole(request.role());

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
         * DTO의 locked 값은 Controller의 @Valid와 @NotNull 검증을
         * 통과한 값이므로 true 또는 false 중 하나
         *
         * Boolean 값은 updateLocked(boolean)에 전달될 때
         * 자동으로 원시 타입 boolean으로 변환
         */
        user.updateLocked(request.locked());

        /*
         * user는 현재 트랜잭션 안에서 조회된 영속 엔티티
         *
         * 트랜잭션 종료 시 JPA 변경 감지가 locked 필드 변경을 확인하여
         * UPDATE SQL을 실행하므로 userRepository.save(user)를
         * 다시 호출할 필요가 없음
         */
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

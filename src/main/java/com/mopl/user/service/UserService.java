package com.mopl.user.service;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.dto.UserCreateRequest;
import com.mopl.user.dto.UserDto;
import com.mopl.user.dto.UserUpdateRequest;
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
     * 이미지가 전달되지 않았으면 기존 프로필 이미지 URL을 유지
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

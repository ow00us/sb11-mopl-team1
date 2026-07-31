package com.mopl.user.service;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.dto.UserCreateRequest;
import com.mopl.user.dto.UserDto;
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

// 사용자 회원가입과 관련된 비즈니스 규칙 처리
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * 이메일·비밀번호 기반 회원가입을 처리
     *
     * 이메일은 공백을 제거하고 소문자로 정규화
     * 비밀번호는 원문 대신 PasswordEncoder로 인코딩한 해시만 저장
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
     *
     * 사용자는 대문자, 앞뒤 공백 포함하여 입력(복붙 시 공백 들어갈 수 있을 때)할 수 있지만
     * 저장과 조회는 항상 같은 정규화 규칙 적용
     */
    private String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

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
     */
    public UserDto getMyProfile(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
            );

        return UserDto.from(user);
    }

}

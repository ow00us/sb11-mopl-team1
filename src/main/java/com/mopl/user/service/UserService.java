package com.mopl.user.service;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.dto.UserCreateRequest;
import com.mopl.user.dto.UserDto;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        String passwordHash = passwordEncoder.encode(request.password());

        User user = User.builder()
            .email(normalizedEmail)
            .passwordHash(passwordHash)
            .name(request.name())
            .role(UserRole.USER)
            .locked(false)
            .build();

        User savedUser = userRepository.save(user);

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

}

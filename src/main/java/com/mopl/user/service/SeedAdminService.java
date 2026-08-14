package com.mopl.user.service;

import com.mopl.user.config.SeedAdminProperties;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * seed 프로파일에서 초기 관리자 계정을 준비하는 서비스
 *
 * <p>환경변수로 전달된 관리자 정보를 users 테이블에 저장하며,
 * 비밀번호 원문은 기존 PasswordEncoder를 이용해 해시한 뒤
 * 해시 값만 User 엔티티에 전달합니다.</p>
 *
 * <p>동일한 관리자 이메일로 여러 번 실행해도 계정을 중복 생성하지
 * 않도록 멱등하게 처리합니다.</p>
 *
 * <p>같은 이메일의 일반 사용자 계정이 이미 존재하는 경우에는
 * 해당 계정을 자동으로 ADMIN으로 승격하지 않습니다. 설정 실수로
 * 기존 사용자에게 관리자 권한이 부여되는 것을 방지하기 위해
 * 명확한 예외를 발생시켜 애플리케이션 시작을 중단합니다.</p>
 */
@Service
@Profile("seed & !prod")
@RequiredArgsConstructor
public class SeedAdminService {

    /**
     * 초기 관리자 환경설정
     */
    private final SeedAdminProperties seedAdminProperties;

    /**
     * 사용자 계정 조회와 저장을 담당하는 Repository
     */
    private final UserRepository userRepository;

    /**
     * 비밀번호 원문을 안전한 해시로 변환하는 기존 공통 Encoder
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * Seed 관리자 계정을 준비
     *
     * <p>동작 순서는 다음과 같습니다.</p>
     *
     * <ol>
     *     <li>환경변수의 이메일을 기존 회원가입과 같은 규칙으로 정규화</li>
     *     <li>동일 이메일의 기존 사용자 조회</li>
     *     <li>기존 ADMIN이면 멱등하게 종료</li>
     *     <li>기존 USER이면 자동 승격하지 않고 시작 실패</li>
     *     <li>계정이 없으면 비밀번호를 해시하고 ADMIN 계정 생성</li>
     * </ol>
     *
     * @return 새 관리자 계정을 생성했으면 true,
     *         이미 동일한 관리자가 존재하면 false
     * @throws IllegalStateException 동일 이메일의 일반 사용자 계정이 존재하는 경우
     */
    @Transactional
    public boolean initializeAdmin() {
        /*
         * 회원가입과 로그인에서 사용하는 이메일 정규화 정책과 동일하게
         * 앞뒤 공백을 제거하고 Locale에 영향받지 않는 소문자로 변환
         */
        String normalizedEmail =
            normalizeEmail(
                seedAdminProperties.getEmail()
            );

        /*
         * 이메일에는 DB UNIQUE 제약이 있으므로 동일 이메일의 계정은
         * 최대 한 개만 존재
         */
        User existingUser =
            userRepository.findByEmail(normalizedEmail)
                .orElse(null);

        if (existingUser != null) {
            /*
             * 이미 같은 이메일의 ADMIN이 존재한다면 Seed Runner를
             * 여러 번 실행한 상황이므로 별도 변경 없이 정상 종료
             *
             * 비밀번호를 다시 인코딩하거나 기존 관리자 정보를 덮어쓰지 않습니다.
             */
            if (existingUser.getRole() == UserRole.ADMIN) {
                return false;
            }

            /*
             * 같은 이메일의 일반 사용자를 자동으로 ADMIN으로 승격하면
             * 환경변수 오타 또는 잘못된 설정만으로 권한 상승이 발생할 수 있습니다.
             *
             * 실제 이메일이나 비밀번호를 예외 메시지에 포함하지 않아
             * 로그를 통한 계정 정보 노출도 방지
             */
            throw new IllegalStateException(
                "Seed 관리자 이메일과 동일한 일반 사용자 계정이 이미 존재합니다."
            );
        }

        /*
         * 기존 계정이 없는 경우에만 비밀번호 원문을 인코딩
         *
         * 이미 관리자가 존재하는 멱등 실행에서는 불필요하게 새로운
         * BCrypt 해시를 생성하지 않습니다.
         */
        String passwordHash =
            passwordEncoder.encode(
                seedAdminProperties.getPassword()
            );

        /*
         * 회원가입과 달리 Seed 관리자 생성이라는 명확한 내부 경로이므로
         * 역할을 ADMIN으로 지정
         *
         * 외부 HTTP 요청에서 role을 받지 않으므로 일반 사용자가
         * 이 경로를 이용해 관리자 권한을 얻을 수 없습니다.
         */
        User admin =
            User.builder()
                .email(normalizedEmail)
                .passwordHash(passwordHash)
                .name(seedAdminProperties.getName())
                .role(UserRole.ADMIN)
                .locked(false)
                .build();

        /*
         * INSERT SQL을 즉시 실행하여 DB의 이메일 UNIQUE 제약과
         * 역할 CHECK 제약 위반을 메서드 실행 중에 확인
         *
         * 저장에 실패하면 트랜잭션이 롤백되고 애플리케이션 시작도
         * 중단되므로 일부만 저장된 초기 데이터가 남지 않습니다.
         */
        userRepository.saveAndFlush(admin);

        return true;
    }

    /**
     * 관리자 이메일을 로그인 식별에 사용할 표준 형태로 변환
     *
     * <p>현재 UserService 회원가입과 동일하게 앞뒤 공백을 제거하고
     * Locale.ROOT 기준으로 소문자로 변환합니다.</p>
     *
     * @param email 환경변수로 전달된 관리자 이메일
     * @return 정규화된 관리자 이메일
     */
    private String normalizeEmail(
        String email
    ) {
        return email.strip()
            .toLowerCase(Locale.ROOT);
    }
}

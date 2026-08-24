package com.mopl.user.service;

import com.mopl.user.dto.ResetPasswordRequest;
import com.mopl.user.entity.User;
import com.mopl.user.mail.TemporaryPasswordEmailSender;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.security.TemporaryPasswordGenerator;
import com.mopl.user.storage.RefreshTokenStore;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이메일을 이용한 사용자 비밀번호 초기화를 처리하는 서비스
 *
 * <p>요청 이메일로 사용자를 조회하고, 암호학적으로 안전한 임시 비밀번호를
 * 생성한 뒤 BCrypt 해시를 사용자 엔티티에 반영합니다. 임시 비밀번호 원문은
 * 데이터베이스나 Redis에 저장하지 않고 이메일 발송에만 사용합니다.</p>
 *
 * <p>메일 발송까지 하나의 트랜잭션 흐름으로 처리합니다. 메일 발송 구현체가
 * 예외를 숨기지 않고 전달하므로 SMTP 발송이 실패하면 비밀번호 변경
 * 트랜잭션도 롤백됩니다.</p>
 *
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    /**
     * 정규화된 이메일로 비밀번호 초기화 대상 사용자를 조회
     */
    private final UserRepository userRepository;

    /**
     * 임시 비밀번호를 로그인에 사용할 수 있는 BCrypt 해시로 변환
     *
     * <p>회원가입 및 로그인과 동일한 PasswordEncoder Bean을 사용해야
     * 임시 비밀번호로 정상 인증할 수 있습니다.</p>
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * 예측하기 어려운 임시 비밀번호 원문을 생성
     */
    private final TemporaryPasswordGenerator
        temporaryPasswordGenerator;

    /**
     * 생성된 임시 비밀번호 원문을 사용자 이메일로 발송
     */
    private final TemporaryPasswordEmailSender
        temporaryPasswordEmailSender;

    /**
     * 비밀번호 초기화 전 기존 로그인 세션을 전부 폐기하는 저장소
     */
    private final RefreshTokenStore refreshTokenStore;

    /**
     * 사용자의 비밀번호를 임시 비밀번호로 초기화하고 이메일로 발송
     *
     * <p>동작 순서는 다음과 같습니다.</p>
     *
     * <ol>
     *     <li>요청 이메일을 조회용 형식으로 정규화합니다.</li>
     *     <li>정규화된 이메일로 사용자를 한 번 조회합니다.</li>
     *     <li>임시 비밀번호 원문을 생성합니다.</li>
     *     <li>임시 비밀번호를 BCrypt 해시로 변환합니다.</li>
     *     <li>기존 Refresh Token Family를 모두 폐기합니다.</li>
     *     <li>User 엔티티에 새 비밀번호 해시를 반영합니다.</li>
     *     <li>임시 비밀번호 원문을 사용자 이메일로 발송합니다.</li>
     * </ol>
     *
     * <p>조회한 User는 현재 트랜잭션에서 관리되는 영속 엔티티이므로
     * {@link User#changePassword(String)} 호출 후
     * {@code userRepository.save(user)}를 다시 호출하지 않습니다.
     * 트랜잭션이 정상 종료되면 JPA 변경 감지가 password_hash UPDATE를
     * 실행합니다.</p>
     *
     * <p>사용자의 계정 잠금 상태와 권한은 변경하지 않습니다. 잠긴 사용자가
     * 비밀번호를 초기화하더라도 관리자에 의해 잠긴 상태는 그대로 유지됩니다.</p>
     *
     * <p>공개 API 응답을 통해 사용자 존재 여부나 로그인 방식을
     * 추측할 수 없도록, 존재하지 않는 사용자와 로컬 로그인 수단이 없는
     * OAuth 전용 사용자는 작업 없이 정상 종료합니다.</p>
     *
     * @param request 비밀번호를 초기화할 사용자 이메일
     */
    @Transactional
    public void resetPassword(
        ResetPasswordRequest request
    ) {
        /*
         * 회원가입과 로그인에서 사용하는 이메일 규칙과 동일하게
         * 앞뒤 공백 제거 후 Locale 영향을 받지 않는 소문자로 변환
         */
        String normalizedEmail =
            normalizeEmail(
                request.email()
            );

        /*
         * 비밀번호 초기화 대상 사용자를 한 번만 조회
         *
         * 공개 API를 통해 계정 존재 여부가 노출되지 않도록
         * 사용자가 없어도 예외를 발생시키지 않는다.
         */
        User user = userRepository.findByEmail(normalizedEmail)
            .orElse(null);

        /*
         * 공개 API 응답만으로 계정 존재 여부나 로그인 방식을
         * 구분할 수 없도록 실제 처리 대상이 아니면 조용히 종료한다.
         */
        if (
            user == null
                || user.getPasswordHash() == null
                || user.getPasswordHash().isBlank()
        ) {
            return;
        }

        /*
         * 이메일로 전달할 임시 비밀번호 원문을 생성
         *
         * 이 원문은 아래 PasswordEncoder와 이메일 발송에만 사용하며
         * 로그, DB 또는 Redis에 저장하지 않는다.
         */
        String temporaryPassword =
            temporaryPasswordGenerator.generate();

        /*
         * 로그인 인증은 users.password_hash와 입력 비밀번호를
         * PasswordEncoder로 비교하므로 임시 비밀번호도 동일한
         * PasswordEncoder Bean으로 인코딩해야 한다.
         */
        String encodedPassword =
            passwordEncoder.encode(
                temporaryPassword
            );

        /*
         * 비밀번호가 초기화되면 기존 인증 정보로 생성된 로그인 세션을
         * 더 이상 신뢰할 수 없으므로 모든 Refresh Token Family를 폐기
         *
         * 비밀번호 해시 변경과 이메일 발송보다 먼저 실행하여
         * Redis 폐기에 실패한 경우 사용자 비밀번호나 이메일 발송이
         * 변경되지 않도록 한다.
         *
         * 이후 이메일 발송이 실패하면 데이터베이스 비밀번호 변경은
         * 롤백되지만 기존 Refresh Token 세션은 폐기된 상태로 남는다.
         * 이는 기존 세션을 남기는 것보다 안전한 fail-closed 정책
         */
        refreshTokenStore.revokeAllByUserId(
            user.getId()
        );

        /*
         * User 엔티티에는 임시 비밀번호 원문이 아닌
         * PasswordEncoder가 만든 해시만 반영
         */
        user.changePassword(
            encodedPassword
        );

        /*
         * 임시 비밀번호 원문은 이메일 발송에만 사용
         *
         * 메일 발송 예외를 이 메서드에서 처리하거나 숨기지 않는다.
         * 예외가 전파되면 @Transactional이 비밀번호 변경을 롤백
         */
        temporaryPasswordEmailSender.send(
            normalizedEmail,
            temporaryPassword
        );
    }

    /**
     * 사용자 이메일을 저장 및 조회에 사용하는 형식으로 정규화
     *
     * <p>{@link Locale#ROOT}를 사용하면 서버 운영체제의 언어 설정과
     * 관계없이 이메일 영문 대소문자 변환 결과를 일정하게 유지할 수
     * 있습니다.</p>
     *
     * @param email 사용자가 입력한 이메일
     * @return 앞뒤 공백이 제거되고 소문자로 변환된 이메일
     */
    private String normalizeEmail(
        String email
    ) {
        return email
            .strip()
            .toLowerCase(
                Locale.ROOT
            );
    }
}

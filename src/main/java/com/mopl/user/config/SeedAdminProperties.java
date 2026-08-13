package com.mopl.user.config;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * seed 프로파일에서 생성할 초기 관리자 계정 설정
 *
 * <p>관리자 이메일, 비밀번호와 이름을 코드에 직접 작성하지 않고
 * 실행 환경의 환경변수로 전달받기 위한 설정 객체입니다.</p>
 *
 * <p>이 설정 객체는 seed 프로파일에서만 Spring Bean으로 등록됩니다.
 * 따라서 기본 실행이나 운영 프로파일에서는 초기 관리자 설정을
 * 읽거나 검증하지 않습니다.</p>
 *
 * <p>관리자 계정도 일반 회원과 동일한 이메일·이름·비밀번호 정책을
 * 따라야 하므로 UserCreateRequest와 동일한 입력 제약을 적용합니다.</p>
 */
@Getter
@Setter
@Component
@Validated
@Profile("seed")
@ConfigurationProperties(prefix = "seed.admin")
public class SeedAdminProperties {

    /**
     * 초기 관리자 화면 표시 이름
     *
     * <p>일반 회원가입 이름 정책과 동일하게 필수 값이며
     * 최대 30자까지 허용합니다.</p>
     */
    @NotBlank(
        message = "Seed 관리자 이름은 반드시 설정해야 합니다."
    )
    @Size(
        max = 30,
        message = "Seed 관리자 이름은 30자 이하로 작성해야 합니다."
    )
    private String name;

    /**
     * 초기 관리자 로그인 이메일
     *
     * <p>실제 값은 SEED_ADMIN_EMAIL 환경변수로 주입합니다.
     * 이메일 정규화는 관리자 계정을 생성하는 Service에서
     * 앞뒤 공백 제거와 소문자 변환 방식으로 수행합니다.</p>
     */
    @NotBlank(
        message = "Seed 관리자 이메일은 반드시 설정해야 합니다."
    )
    @Email(
        message = "Seed 관리자 이메일 형식이 올바르지 않습니다."
    )
    @Size(
        max = 100,
        message = "Seed 관리자 이메일은 100자 이하로 작성해야 합니다."
    )
    private String email;

    /**
     * 초기 관리자 로그인 비밀번호 원문
     *
     * <p>실제 값은 SEED_ADMIN_PASSWORD 환경변수로 주입하며
     * 코드, Git 저장소와 로그에 기록하지 않습니다.</p>
     *
     * <p>일반 회원가입과 동일하게 8~72자의 ASCII 영문·숫자·특수문자를
     * 각각 하나 이상 포함해야 합니다. 실제 저장 시에는 기존
     * PasswordEncoder로 인코딩한 해시만 users 테이블에 저장합니다.</p>
     */
    @NotBlank(
        message = "Seed 관리자 비밀번호는 반드시 설정해야 합니다."
    )
    @Size(
        min = 8,
        max = 72,
        message = "Seed 관리자 비밀번호는 8~72자로 작성해야 합니다."
    )
    @Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)"
            + "(?=.*[!@#$%^&*()_+\\-={}\\[\\]|:;\"'<>,.?/~`])"
            + "[A-Za-z\\d!@#$%^&*()_+\\-={}\\[\\]|:;\"'<>,.?/~`]+$",
        message = "Seed 관리자 비밀번호는 영문, 숫자, 특수문자를 "
            + "각각 하나 이상 포함해야 합니다."
    )
    private String password;
}

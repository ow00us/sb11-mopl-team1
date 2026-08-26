package com.mopl.user.entity;

import com.mopl.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자 계정을 표현하는 JPA 엔티티
 *
 * 이 클래스의 객체는 PostgreSQL의 {@code users} 테이블 행과 연결
 * 이메일, 비밀번호 해시, 이름, 프로필 이미지, 권한 및 잠금 상태를 관리
 */
@Entity
@Getter
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    // 로그인 ID로 사용하는 사용자의 이메일
    // 회원가입 서비스에서 앞뒤 공백 제거 후 소문자로 변환한 뒤 이 필드에 저장
    @Column(nullable = false, length = 255)
    private String email;

    /*
     * 로컬 이메일·비밀번호 로그인에 사용하는 BCrypt 비밀번호 해시
     *
     * 로컬 회원가입 사용자는 반드시 비밀번호 해시를 저장
     * OAuth로만 가입한 사용자는 로컬 비밀번호가 없으므로 null일 수 있다.
     *
     * 비밀번호 원문은 어떤 경우에도 이 필드에 저장하지 않는다.
     */
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    // 서비스 화면에 표시할 사용자 이름
    @Column(nullable = false, length = 100)
    private String name;

    // 사용자 프로필 이미지의 URL
    @Column(name = "profile_image_url", length = 2048)
    private String profileImageUrl;

    // 사용자의 시스템 권한
    // enum 순서 번호를 저장하는 ORDINAL 방식 사용 시 enum 선언 순서를 변경했을 때
    // 기존 데이터의 의미가 바뀔 수 있으므로 권한같은 중요한 값에는 STRING 방식 사용
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    // 관리자에 의한 계정 잠금 여부
    // true면 로그인 불허, false면 허용. 기본값 false
    @Column(nullable = false)
    private boolean locked;

    /**
     * User 엔티티를 명시적인 값으로 생성하기 위한 생성자
     * <p>
     * {@link Builder}를 적용하여 필드 순서에 의존하지 않고 의미가 드러나는 방식으로 객체를 만들 수 있습니다.
     * <p>
     * User user = User.builder() .email("user@example.com") .passwordHash("encoded-password")
     * .name("사용자") .role(UserRole.USER) .locked(false) .build();
     *
     * @param email           정규화된 이메일
     * @param passwordHash    인코딩된 비밀번호, OAuth 전용 사용자는 null
     * @param name            사용자 이름
     * @param profileImageUrl 프로필 이미지 URL, 없으면 null
     * @param role            사용자 권한
     * @param locked          계정 잠금 여부
     */
    @Builder
    public User(
        String email,
        String passwordHash,
        String name,
        String profileImageUrl,
        UserRole role,
        boolean locked
    ) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.profileImageUrl = profileImageUrl;
        this.role = role;
        this.locked = locked;
    }

    /**
     * 사용자의 프로필 정보를 변경
     *
     * PATCH 요청에서는 이름과 프로필 이미지가 선택적으로 전달될 수 있으므로,
     * 전달된 값만 변경하고 null인 값은 기존 정보를 유지
     *
     * 프로필 이미지 파일 자체는 Service에서 저장소에 업로드하고,
     * 이 메서드는 업로드 결과로 받은 URL만 엔티티에 반영
     *
     * @param name 변경할 사용자 이름, null이면 기존 이름 유지
     * @param profileImageUrl 변경할 프로필 이미지 URL, null이면 기존 이미지 유지
     *
     * 필드에 setter를 열어두지 않고 프로필 변경이라는 의미가 드러나는 메서드를 통해서만 상태를 바꾸기 위함.
     */
    public void updateProfile(
        String name,
        String profileImageUrl
    ) {
        if (name != null) {
            this.name = name;
        }

        if (profileImageUrl != null) {
            this.profileImageUrl = profileImageUrl;
        }
    }

    /**
     * OAuth 전용 사용자에게 로컬 이메일·비밀번호 로그인 수단을 등록
     *
     * <p>OAuth 전용 사용자의 내부 식별 이메일을 사용자가 인증한
     * 실제 이메일로 교체하고, PasswordEncoder로 생성된 비밀번호
     * 해시를 함께 저장합니다.</p>
     *
     * <p>이미 passwordHash가 존재하는 사용자에게 다시 호출하면
     * 이메일 변경 기능으로 오용될 수 있으므로 거부합니다.</p>
     *
     * @param normalizedEmail 소유권 인증을 마친 정규화된 실제 이메일
     * @param encodedPassword PasswordEncoder로 생성한 비밀번호 해시
     */
    public void registerLocalCredential(
        String normalizedEmail,
        String encodedPassword
    ) {
        if (
            normalizedEmail == null
                || normalizedEmail.isBlank()
        ) {
            throw new IllegalArgumentException(
                "로컬 로그인 이메일은 비어 있을 수 없습니다."
            );
        }

        if (
            encodedPassword == null
                || encodedPassword.isBlank()
        ) {
            throw new IllegalArgumentException(
                "로컬 로그인 비밀번호 해시는 비어 있을 수 없습니다."
            );
        }

        if (passwordHash != null) {
            throw new IllegalStateException(
                "이미 로컬 로그인 수단이 등록되어 있습니다."
            );
        }

        this.email = normalizedEmail;
        this.passwordHash = encodedPassword;
    }


    /**
     * 사용자의 비밀번호 해시를 새로운 값으로 변경
     *
     * User 엔티티에는 비밀번호 원문을 저장하지 않는다.
     * Service에서 PasswordEncoder로 인코딩한 결과만 이 메서드에 전달해야 한다.
     *
     * 범용 setter를 제공하지 않고 비밀번호 변경 목적이 드러나는
     * 메서드를 통해서만 passwordHash를 변경
     *
     * @param encodedPassword 새 비밀번호를 인코딩한 해시
     */
    public void changePassword(
        String encodedPassword
    ) {
        this.passwordHash = encodedPassword;
    }

    /**
     * 관리자의 요청에 따라 사용자의 권한을 변경
     *
     * 현재 시스템에서 사용할 수 있는 권한은
     * UserRole enum에 정의된 USER와 ADMIN
     *
     * role 필드에 범용 setter를 제공하지 않고
     * 사용자 권한 변경이라는 목적이 드러나는 메서드를 통해서만
     * 권한을 변경하도록 제한
     *
     * 전달되는 role 값의 null 여부는
     * UserRoleUpdateRequest의 @NotNull 검증에서 먼저 확인
     *
     * @param role 새로 적용할 사용자 권한
     */
    public void updateRole(
        UserRole role
    ) {
        this.role = role;
    }

    /**
     * 관리자의 요청에 따라 사용자 계정의 잠금 상태를 변경
     *
     * true를 전달하면 계정을 잠그고,
     * false를 전달하면 기존 계정 잠금을 해제
     *
     * locked 필드에 범용 setter를 제공하지 않고
     * 계정 잠금 상태 변경이라는 목적이 드러나는 메서드를 통해서만 값을 변경
     *
     * @param locked 새로 적용할 계정 잠금 상태
     */
    public void updateLocked(
        boolean locked
    ) {
        this.locked = locked;
    }
}

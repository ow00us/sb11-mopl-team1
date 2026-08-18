package com.mopl.user.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.service.SeedAdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * seed 프로파일로 애플리케이션을 실행했을 때
 * 초기 관리자 계정이 실제 PostgreSQL에 생성되는지 검증
 *
 * <p>이 테스트는 SeedAdminRunner, SeedAdminService,
 * UserRepository, PasswordEncoder와 실제 PostgreSQL 스키마를
 * 함께 사용하는 통합 테스트입니다.</p>
 *
 * <p>단위 테스트에서는 각 클래스의 개별 규칙을 확인하고,
 * 이 테스트에서는 애플리케이션 기동부터 데이터베이스 저장까지
 * 전체 연결이 정상적으로 동작하는지 확인합니다.</p>
 */
@SpringBootTest(
    properties = {
        /*
         * 실제 관리자 정보는 환경변수로 주입하지만,
         * 테스트에서는 재현 가능한 고정 테스트 값을 사용
         *
         * 이 값은 테스트 전용이며 실제 시연·운영 계정 정보가 아닙니다.
         */
        "seed.admin.name=시연 관리자",
        "seed.admin.email=seed.admin@example.com",
        "seed.admin.password=SeedAdmin1!"
    }
)
@ActiveProfiles({
    "test",
    "seed"
})
@Testcontainers
class SeedAdminIntegrationTest {

    /**
     * 통합 테스트에서 사용할 실제 PostgreSQL 컨테이너
     *
     * <p>@ServiceConnection이 컨테이너의 JDBC 주소,
     * 사용자 이름과 비밀번호를 Spring Boot DataSource에
     * 자동으로 연결합니다.</p>
     */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(
            "postgres:16"
        );

    /**
     * Runner가 저장한 관리자 계정을 실제 DB에서 조회하기 위한 Repository
     */
    @Autowired
    UserRepository userRepository;

    /**
     * 저장된 BCrypt 해시가 테스트 비밀번호 원문과 일치하는지
     * 검증하기 위한 기존 공통 PasswordEncoder
     */
    @Autowired
    PasswordEncoder passwordEncoder;

    /**
     * 같은 설정으로 초기화 로직을 다시 실행했을 때
     * 중복 생성되지 않는지 검증하기 위한 Service
     */
    @Autowired
    SeedAdminService seedAdminService;

    @Test
    @DisplayName(
        "seed 프로파일로 기동하면 ADMIN 계정을 생성하고 재실행해도 중복 생성하지 않는다"
    )
    void seedProfile_createsAdminAndRemainsIdempotent() {
        // given & when
        /*
         * @SpringBootTest가 애플리케이션 컨텍스트를 준비하는 과정에서
         * SeedAdminRunner.run()이 이미 실행
         *
         * 따라서 테스트 메서드가 시작된 시점에는 SeedAdminRunner가
         * 생성한 관리자 계정이 PostgreSQL에 저장돼 있어야 합니다.
         */
        User savedAdmin =
            userRepository.findByEmail(
                "seed.admin@example.com"
            ).orElseThrow();

        // then
        /*
         * 환경설정으로 전달한 관리자 정보가 users 테이블에
         * 올바르게 저장됐는지 확인
         */
        assertThat(savedAdmin.getEmail())
            .isEqualTo("seed.admin@example.com");

        assertThat(savedAdmin.getName())
            .isEqualTo("시연 관리자");

        assertThat(savedAdmin.getRole())
            .isEqualTo(UserRole.ADMIN);

        assertThat(savedAdmin.isLocked())
            .isFalse();

        assertThat(savedAdmin.getId())
            .isNotNull();

        assertThat(savedAdmin.getCreatedAt())
            .isNotNull();

        assertThat(savedAdmin.getUpdatedAt())
            .isNotNull();

        /*
         * 데이터베이스에는 비밀번호 원문이 저장되면 안 됩니다.
         */
        assertThat(savedAdmin.getPasswordHash())
            .isNotEqualTo("SeedAdmin1!");

        /*
         * 저장된 값이 임의의 문자열이 아니라 실제로 공통
         * PasswordEncoder가 만든 유효한 비밀번호 해시인지 확인
         */
        assertThat(
            passwordEncoder.matches(
                "SeedAdmin1!",
                savedAdmin.getPasswordHash()
            )
        ).isTrue();

        /*
         * 테스트용 PostgreSQL은 빈 상태로 시작했으므로
         * Runner 실행 후 사용자 행은 관리자 한 건이어야 합니다.
         */
        assertThat(userRepository.count())
            .isEqualTo(1L);

        // when
        /*
         * 애플리케이션을 다시 실행한 상황을 재현하기 위해
         * 같은 초기화 서비스를 한 번 더 호출
         *
         * 동일 이메일의 ADMIN이 이미 존재하므로 false를 반환해야 합니다.
         */
        boolean createdAgain =
            seedAdminService.initializeAdmin();

        // then
        assertThat(createdAgain)
            .isFalse();

        /*
         * 재실행 후에도 사용자 행이 추가되지 않아야 합니다.
         */
        assertThat(userRepository.count())
            .isEqualTo(1L);

        User adminAfterSecondInitialization =
            userRepository.findByEmail(
                "seed.admin@example.com"
            ).orElseThrow();

        /*
         * 기존 관리자 계정을 덮어쓰거나 새로 만들지 않고
         * 처음 생성된 동일한 계정을 유지하는지 UUID로 확인
         */
        assertThat(
            adminAfterSecondInitialization.getId()
        ).isEqualTo(savedAdmin.getId());

        assertThat(
            adminAfterSecondInitialization.getPasswordHash()
        ).isEqualTo(savedAdmin.getPasswordHash());
    }
}

package com.mopl.user.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.user.service.SeedAdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.annotation.Profile;

/**
 * Seed 초기 관리자 Runner의 실행 조건과 서비스 호출을 검증
 *
 * <p>실제 사용자 조회와 저장 규칙은 SeedAdminServiceTest에서 검증하므로,
 * 이 테스트에서는 애플리케이션 시작 시 서비스가 호출되는지와
 * 서비스에서 발생한 오류가 정상적으로 전파되는지를 확인합니다.</p>
 */
@ExtendWith(MockitoExtension.class)
class SeedAdminRunnerTest {

    /**
     * 초기 관리자 생성 작업을 수행하는 서비스 Mock
     */
    @Mock
    SeedAdminService seedAdminService;

    /**
     * ApplicationRunner 실행 시 전달되는 애플리케이션 인자 Mock
     *
     * SeedAdminRunner는 현재 실행 인자를 사용하지 않지만,
     * ApplicationRunner의 run() 메서드 계약에 맞춰 전달
     */
    @Mock
    ApplicationArguments applicationArguments;

    /**
     * 위 Mock 객체를 생성자를 통해 주입한 테스트 대상 Runner
     */
    @InjectMocks
    SeedAdminRunner seedAdminRunner;

    @Test
    @DisplayName("Seed 관리자 Runner는 seed이면서 prod가 아닐 때만 등록된다")
    void runnerIsRegisteredOnlyInNonProductionSeedProfile() {
        // given & when
        Profile profile =
            SeedAdminRunner.class
                .getAnnotation(Profile.class);

        // then
        /*
         * Runner가 기본 프로파일이나 운영 프로파일에서 실행되면
         * 의도하지 않은 관리자 계정 생성 시도가 발생할 수 있으므로
         * 프로파일 제한 선언 자체를 검증
         */
        assertThat(profile)
            .isNotNull();

        assertThat(profile.value())
            .containsExactly("seed & !prod");
    }

    @Test
    @DisplayName("애플리케이션 시작 시 초기 관리자 생성 서비스를 호출한다")
    void run_callsSeedAdminService() {
        // given
        /*
         * 관리자 계정이 새로 생성된 상황을 의미
         */
        when(seedAdminService.initializeAdmin())
            .thenReturn(true);

        // when
        seedAdminRunner.run(applicationArguments);

        // then
        /*
         * Runner가 직접 사용자 저장 로직을 수행하지 않고
         * 관리자 생성 책임을 Service에 위임하는지 확인
         */
        verify(seedAdminService)
            .initializeAdmin();
    }

    @Test
    @DisplayName("기존 관리자가 존재해 생성을 건너뛰어도 정상 종료한다")
    void run_completesNormallyWhenAdminAlreadyExists() {
        // given
        /*
         * initializeAdmin()의 false는 동일 이메일의 ADMIN 계정이
         * 이미 존재하여 새 계정을 만들지 않았다는 의미
         */
        when(seedAdminService.initializeAdmin())
            .thenReturn(false);

        // when & then
        /*
         * 이미 관리자가 존재하는 것은 오류가 아니라 정상적인
         * 멱등 실행이므로 Runner는 예외 없이 종료돼야 합니다.
         */
        assertThatCode(() ->
            seedAdminRunner.run(applicationArguments)
        ).doesNotThrowAnyException();

        verify(seedAdminService)
            .initializeAdmin();
    }

    @Test
    @DisplayName("초기 관리자 생성에 실패하면 예외를 숨기지 않고 전파한다")
    void run_propagatesInitializationFailure() {
        // given
        IllegalStateException initializationFailure =
            new IllegalStateException(
                "Seed 관리자 계정 초기화 실패"
            );

        when(seedAdminService.initializeAdmin())
            .thenThrow(initializationFailure);

        // when & then
        /*
         * 초기 관리자 생성에 실패했는데 예외를 삼키면 애플리케이션은
         * 관리자 계정이 없는 상태로 기동될 수 있습니다.
         *
         * 따라서 Runner가 예외를 잡아 무시하지 않고 Spring Boot
         * 시작 과정으로 그대로 전파하는지 확인
         */
        assertThatThrownBy(() ->
            seedAdminRunner.run(applicationArguments)
        )
            .isSameAs(initializationFailure);

        verify(seedAdminService)
            .initializeAdmin();
    }
}

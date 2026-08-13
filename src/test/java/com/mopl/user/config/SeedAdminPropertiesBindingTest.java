package com.mopl.user.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * SeedAdminProperties의 프로파일 조건과 설정값 바인딩을 검증합니다.
 *
 * <p>순수 Validator 테스트와 달리 실제 Spring ApplicationContext를
 * 실행하여 @Profile 조건, @ConfigurationProperties 바인딩과
 * 시작 시점 검증이 함께 동작하는지 확인합니다.</p>
 */
class SeedAdminPropertiesBindingTest {

    /**
     * ConfigurationProperties 바인딩에 필요한 자동 설정과
     * SeedAdminProperties만 등록한 최소 테스트 Context를 생성합니다.
     *
     * <p>전체 애플리케이션을 실행하지 않으므로 PostgreSQL, Redis와
     * Kafka 같은 외부 인프라가 필요하지 않습니다.</p>
     *
     * @return Seed 관리자 설정 검증용 ApplicationContextRunner
     */
    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class
                )
            )
            .withUserConfiguration(
                SeedAdminProperties.class
            );
    }

    @Test
    @DisplayName("seed 프로파일에서는 관리자 환경설정이 정상적으로 바인딩된다")
    void seedProfile_bindsAdminProperties() {
        contextRunner()
            /*
             * 기존 dev 설정에 seed 프로파일을 추가한 실행 환경을 재현합니다.
             */
            .withInitializer(context ->
                context.getEnvironment()
                    .setActiveProfiles("dev", "seed")
            )
            .withPropertyValues(
                "seed.admin.name=시연 관리자",
                "seed.admin.email=ADMIN@EXAMPLE.COM",
                "seed.admin.password=SeedAdmin1!"
            )
            .run(context -> {
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(SeedAdminProperties.class);

                SeedAdminProperties properties =
                    context.getBean(
                        SeedAdminProperties.class
                    );

                assertThat(properties.getName())
                    .isEqualTo("시연 관리자");

                assertThat(properties.getEmail())
                    .isEqualTo("ADMIN@EXAMPLE.COM");

                assertThat(properties.getPassword())
                    .isEqualTo("SeedAdmin1!");
            });
    }

    @Test
    @DisplayName("seed 프로파일이 없으면 관리자 설정 Bean을 등록하지 않는다")
    void defaultProfile_doesNotRegisterAdminProperties() {
        contextRunner()
            /*
             * 값이 존재하더라도 seed 프로파일이 아니라면
             * 관리자 설정 Bean이 등록되면 안 됩니다.
             */
            .withPropertyValues(
                "seed.admin.name=시연 관리자",
                "seed.admin.email=admin@example.com",
                "seed.admin.password=SeedAdmin1!"
            )
            .run(context -> {
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(
                        SeedAdminProperties.class
                    );
            });
    }

    @Test
    @DisplayName("prod와 seed가 함께 활성화돼도 관리자 설정 Bean을 등록하지 않는다")
    void productionProfile_doesNotRegisterAdminProperties() {
        contextRunner()
            /*
             * 배포 설정 실수로 prod와 seed가 동시에 활성화되더라도
             * Seed 관리자 계정 생성 기능이 운영에서 실행되지 않도록 합니다.
             */
            .withInitializer(context ->
                context.getEnvironment()
                    .setActiveProfiles("prod", "seed")
            )
            .withPropertyValues(
                "seed.admin.name=시연 관리자",
                "seed.admin.email=admin@example.com",
                "seed.admin.password=SeedAdmin1!"
            )
            .run(context -> {
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(
                        SeedAdminProperties.class
                    );
            });
    }

    @Test
    @DisplayName("seed 프로파일에서 필수 관리자 설정이 누락되면 시작에 실패한다")
    void seedProfile_failsWhenRequiredPropertyIsMissing() {
        contextRunner()
            .withInitializer(context ->
                context.getEnvironment()
                    .setActiveProfiles("dev", "seed")
            )
            /*
             * 비밀번호를 의도적으로 누락합니다.
             */
            .withPropertyValues(
                "seed.admin.name=시연 관리자",
                "seed.admin.email=admin@example.com"
            )
            .run(context -> {
                assertThat(context)
                    .hasFailed();

                assertThat(
                    context.getStartupFailure()
                ).hasRootCauseInstanceOf(
                    BindValidationException.class
                );
            });
    }

    @Test
    @DisplayName("seed 프로파일에서 유효하지 않은 비밀번호면 시작에 실패한다")
    void seedProfile_failsWhenPasswordIsInvalid() {
        contextRunner()
            .withInitializer(context ->
                context.getEnvironment()
                    .setActiveProfiles("dev", "seed")
            )
            .withPropertyValues(
                "seed.admin.name=시연 관리자",
                "seed.admin.email=admin@example.com",
                "seed.admin.password=password"
            )
            .run(context -> {
                assertThat(context)
                    .hasFailed();

                assertThat(
                    context.getStartupFailure()
                ).hasRootCauseInstanceOf(
                    BindValidationException.class
                );
            });
    }
}

package com.mopl.user.seed;

import com.mopl.user.service.SeedAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * seed 프로파일로 애플리케이션을 실행할 때
 * 초기 관리자 계정 생성을 요청하는 시작 작업
 *
 * <p>실제 관리자 생성 규칙과 데이터베이스 처리는
 * {@link SeedAdminService}가 담당합니다.</p>
 *
 * <p>이 클래스는 애플리케이션 시작 시점에 서비스를 호출하고,
 * 관리자 계정이 새로 생성됐는지 또는 이미 존재하는지만
 * 로그로 남기는 역할을 담당합니다.</p>
 *
 * <p>관리자 이메일과 비밀번호 등 민감한 설정값은
 * 로그에 포함하지 않습니다.</p>
 */
@Slf4j
@Component
@Profile("seed & !prod")
@RequiredArgsConstructor
public class SeedAdminRunner implements ApplicationRunner {

    /**
     * 초기 관리자 계정의 조회와 생성을 담당하는 서비스
     */
    private final SeedAdminService seedAdminService;

    /**
     * Spring Boot 애플리케이션 컨텍스트가 준비된 후 실행
     *
     * <p>SeedAdminService의 반환값에 따라 관리자 계정이
     * 새로 생성됐는지, 이미 존재해서 생성을 건너뛰었는지를
     * 구분하여 로그를 남깁니다.</p>
     *
     * <p>서비스에서 예외가 발생해도 이 메서드에서 잡지 않습니다.
     * 같은 이메일의 일반 사용자 계정이 존재하거나 데이터베이스 저장에
     * 실패한 상태에서 애플리케이션이 정상 기동된 것처럼 보이면
     * 관리자 계정이 없는 상태로 시연을 시작할 수 있기 때문입니다.</p>
     *
     * @param args 애플리케이션 실행 시 전달된 인자
     */
    @Override
    public void run(ApplicationArguments args) {
        boolean created =
            seedAdminService.initializeAdmin();

        if (created) {
            /*
             * 이메일이나 비밀번호는 운영 로그에 남기지 않습니다.
             * 계정이 생성됐다는 결과만 기록
             */
            log.info("Seed 초기 관리자 계정을 생성했습니다.");
            return;
        }

        /*
         * 이미 같은 이메일의 ADMIN 계정이 존재하는 경우
         * 정상적인 멱등 실행이므로 오류가 아닌 정보 로그를 남깁니다.
         */
        log.info(
            "Seed 초기 관리자 계정이 이미 존재하여 생성을 건너뜁니다."
        );
    }
}

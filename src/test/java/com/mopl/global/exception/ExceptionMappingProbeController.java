package com.mopl.global.exception;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 예외 상태 코드 매핑을 검증하기 위한 테스트 전용 Controller 입니다.
 *
 * 실제 Controller 와 경로가 겹치지 않도록 별도 경로를 쓰고,
 * exception-mapping-test 프로파일에서만 Bean 으로 등록합니다.
 */
@RestController
@RequestMapping("/api/exception-probe")
@Profile("exception-mapping-test")
class ExceptionMappingProbeController {

    /** GET 만 매핑해 두어 다른 메서드로 호출하면 405 가 발생합니다. */
    @GetMapping("/resource")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resource() {
    }

    /** JSON 만 소비하므로 다른 Content-Type 으로 호출하면 415 가 발생합니다. */
    @PatchMapping(path = "/json", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void json(@RequestBody String body) {
    }

    /** 본문을 JSON 으로만 만들 수 있으므로 Accept 가 JSON 을 허용하지 않으면 406 이 발생합니다. */
    @GetMapping("/body")
    Map<String, String> body() {
        return Map.of("value", "ok");
    }

    /** 오류 응답이 Accept 와 무관하게 직렬화되는지 확인하기 위한 경로입니다. */
    @GetMapping("/business-error")
    void businessError() {
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
}

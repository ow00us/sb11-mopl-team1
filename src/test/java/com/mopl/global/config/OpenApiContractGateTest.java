package com.mopl.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 계약에만 있는 operation 을 게이트가 잡는지 검증합니다.
 *
 * <p>{@link OpenApiRuntimeContractTest} 는 저장소의 실제 계약 파일과 런타임 문서를 비교하므로,
 * 지금처럼 둘이 맞는 상태에서는 게이트가 동작하는지 알 수 없습니다. 어긋난 계약을 직접 만들어
 * 넣어야 판정이 확인됩니다.
 *
 * <p>실제 계약 파일에 미구현 operation 을 넣어 확인할 수는 없습니다. 그건 저장소의 계약을
 * 틀린 상태로 만드는 일입니다.
 */
class OpenApiContractGateTest {

    private static final String PATH = "/api/things";

    private Map<String, Object> agreedPaths(Map<String, Object> operation) {
        Map<String, Object> pathItem = new LinkedHashMap<>();
        pathItem.put("get", operation);
        return Map.of(PATH, pathItem);
    }

    /** 이 구멍이 이슈의 원인입니다. 프론트엔드는 계약을 보고 없는 API 를 호출했습니다. */
    @Test
    @DisplayName("계약에만 있고 구현이 없는 operation을 잡는다")
    void detectsOperationMissingFromRuntime() {
        List<String> differences = OpenApiRuntimeContractTest.compareAgreedOperations(
            Set.of(), agreedPaths(Map.of("summary", "미구현")));

        assertThat(differences).containsExactly("GET " + PATH + " 이(가) 계약에만 있고 구현이 없음");
    }

    @Test
    @DisplayName("구현이 있으면 통과한다")
    void passesWhenImplemented() {
        List<String> differences = OpenApiRuntimeContractTest.compareAgreedOperations(
            Set.of("GET " + PATH), agreedPaths(Map.of("summary", "구현됨")));

        assertThat(differences).isEmpty();
    }

    /**
     * 아직 구현하지 않은 API 를 계약에 먼저 두어야 할 때가 있습니다. 그 사실이 계약 파일에
     * 드러나면 통과시킵니다.
     */
    @Test
    @DisplayName("planned로 표시한 operation은 구현이 없어도 통과한다")
    void allowsPlannedOperation() {
        List<String> differences = OpenApiRuntimeContractTest.compareAgreedOperations(
            Set.of(), agreedPaths(Map.of("x-implementation-status", "planned")));

        assertThat(differences).isEmpty();
    }

    /**
     * 표시를 지우지 않으면 계약이 실제와 어긋난 채 굳습니다.
     */
    @Test
    @DisplayName("planned 표시가 남아 있는데 구현이 생기면 잡는다")
    void detectsStalePlannedMarker() {
        List<String> differences = OpenApiRuntimeContractTest.compareAgreedOperations(
            Set.of("GET " + PATH), agreedPaths(Map.of("x-implementation-status", "planned")));

        assertThat(differences)
            .containsExactly("GET " + PATH + " 이(가) 구현되었는데 계약에 planned 로 남아 있음");
    }

    /**
     * 값이 틀리면 조용히 제외되는 대신 검사에 걸려야 합니다. 오타로 게이트가 뚫리는 것이 이
     * 이슈에서 고치려는 상황입니다.
     */
    @Test
    @DisplayName("planned가 아닌 값은 구현 대상으로 본다")
    void treatsUnknownStatusAsImplemented() {
        List<String> differences = OpenApiRuntimeContractTest.compareAgreedOperations(
            Set.of(), agreedPaths(Map.of("x-implementation-status", "planed")));

        assertThat(differences).containsExactly("GET " + PATH + " 이(가) 계약에만 있고 구현이 없음");
    }

    @Test
    @DisplayName("api 경로가 아닌 항목은 대조하지 않는다")
    void ignoresNonApiPaths() {
        List<String> differences = OpenApiRuntimeContractTest.compareAgreedOperations(
            Set.of(), Map.of("/actuator/health", Map.of("get", Map.of())));

        assertThat(differences).isEmpty();
    }
}

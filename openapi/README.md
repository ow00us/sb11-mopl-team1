# Mopl API 계약

이 디렉터리는 제공된 원본과 팀에서 합의한 구현 기준을 분리해 관리합니다.

## 파일

- `reference/provided-openapi.json`: 프로토타입에서 제공된 OpenAPI 3.1 원본입니다. 비교 기준이므로 수정하지 않습니다.
- `mopl-api.yaml`: 프론트엔드와 백엔드가 함께 따르는 API 계약입니다.

프로토타입 원본의 SHA-256은
`3C9725909B44B3D3FC562F3D4A7551D215F9D0513A13B26143B2E5FAAB2943E1`입니다.

백엔드 실행 후에는 `/swagger-ui.html`에서 Swagger UI를,
`/v3/api-docs`에서 현재 구현의 OpenAPI 문서를 확인할 수 있습니다.

## 변경 방법

1. API 경로, 메서드, 필드, 상태 코드 또는 인증 조건의 변경을 팀에서 합의합니다.
2. 결정 근거가 달라지면 ADR을 갱신하거나 새 ADR을 작성합니다.
3. `mopl-api.yaml`을 구현보다 먼저 또는 같은 PR에서 수정합니다.
4. 백엔드 구현과 Swagger 설명, 프론트엔드 연동 내용을 함께 확인합니다.
5. 계약 검증 테스트를 통과시킨 뒤 병합합니다.

`reference/provided-openapi.json`은 변경하지 않습니다.

## 런타임 계약 검증

`OpenApiRuntimeContractTest`는 발표 대상 REST Controller로 생성한
`/v3/api-docs`와 `mopl-api.yaml`을 비교합니다. Gradle 기본 테스트에 포함되므로
별도 CI 명령 없이도 `build` 단계에서 계약 이탈을 차단합니다.

현재 비교하는 항목은 다음과 같습니다.

- 구현된 REST API의 경로와 HTTP 메서드
- 요청 본문의 Content-Type
- 2xx 성공 상태 코드
- 204 응답에 본문이 문서화되지 않는지 여부
- Bearer JWT와 CSRF 보안 요구

정적 계약에만 있고 아직 구현되지 않은 API는 실패시키지 않습니다. 운영 REST
Controller 목록과 `OpenApiRuntimeContractTest`의 `@WebMvcTest` 대상은 테스트가
자동으로 대조합니다. 새 Controller가 누락되면 테스트가 실패하며, 해당 Controller와
Mock 의존성을 등록해야 합니다. 구현 API를 추가하거나 제거할 때는
`EXPECTED_IMPLEMENTED_OPERATIONS`도 함께 갱신해 변경이 명시적으로 검토되도록
합니다. 샘플 API와 WebSocket·STOMP·SSE 계약, DTO 스키마 전체 비교는 이 검증의
대상이 아닙니다.

불일치를 예외 목록으로 숨기지 않습니다. 실제 동작이 맞다면 Controller의
OpenAPI 응답 설명 또는 `mopl-api.yaml`을 같은 기능 PR에서 수정합니다. 어느 쪽이
맞는지 합의되지 않았다면 계약을 임의로 바꾸지 않고 관련 도메인과 먼저
확정합니다.

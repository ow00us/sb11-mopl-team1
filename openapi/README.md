# Mopl API 계약

이 디렉터리는 제공된 원본과 팀에서 합의한 구현 기준을 분리해 관리합니다.

## 파일

- `reference/provided-openapi.json`: 프로토타입에서 제공된 OpenAPI 3.1 원본입니다. 비교 기준이므로 수정하지 않습니다.
- `mopl-api.yaml`: 프론트엔드와 백엔드가 함께 따르는 API 계약입니다.

프로토타입 원본의 SHA-256은
`3C9725909B44B3D3FC562F3D4A7551D215F9D0513A13B26143B2E5FAAB2943E1`입니다.

## 관련 문서

- [ADR-001 API 계약 및 상태 코드 규칙](https://app.notion.com/p/ADR-001-API-3abd250dfa2681f0b4cfc7e4ab64168b): 계약을 선택한 이유, 상태 코드와 인증 규칙
- [Mopl Swagger 및 API 계약 관리 계획](https://app.notion.com/p/Mopl-Swagger-API-3abd250dfa26810ca57bc5d184485bfa): 작업 범위와 진행 방법
- [프로토타입 Swagger](https://project.sb.sprint.learn.codeit.kr/sb/mopl/api/swagger-ui/index.html): 제공된 원본 동작 확인

백엔드 실행 후에는 `/swagger-ui.html`에서 Swagger UI를,
`/v3/api-docs`에서 현재 구현의 OpenAPI 문서를 확인할 수 있습니다.

## 변경 방법

1. API 경로, 메서드, 필드, 상태 코드 또는 인증 조건의 변경을 팀에서 합의합니다.
2. 결정 근거가 달라지면 ADR을 갱신하거나 새 ADR을 작성합니다.
3. `mopl-api.yaml`을 구현보다 먼저 또는 같은 PR에서 수정합니다.
4. 백엔드 구현과 Swagger 설명, 프론트엔드 연동 내용을 함께 확인합니다.
5. 계약 검증 테스트를 통과시킨 뒤 병합합니다.

`reference/provided-openapi.json`은 변경하지 않습니다.

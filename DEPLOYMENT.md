# MOPL 애플리케이션 이미지 실행

이 문서는 Spring Boot 애플리케이션 이미지를 빌드하고 `prod` 프로파일로 실행하는 데 필요한 계약을 정리합니다. 이미지 게시와 실제 배포 자동화는 별도 작업에서 다룹니다.

## 이미지 빌드

```bash
docker build --pull -t mopl:local .
```

Dockerfile은 빌드 단계와 실행 단계를 분리합니다. 실행 이미지는 `mopl` 비특권 사용자로 애플리케이션을 실행하며 `/actuator/health`를 Docker healthcheck로 사용합니다.

## 필수 환경 변수

| 변수 | 설명 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | 운영 실행 시 `prod` |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL 사용자 |
| `SPRING_DATASOURCE_PASSWORD` | PostgreSQL 비밀번호 |
| `SPRING_DATA_REDIS_HOST` | Redis 호스트 |
| `SPRING_DATA_REDIS_PORT` | Redis 포트 |
| `JWT_SECRET` | HS256용 32바이트 이상 키를 Base64로 인코딩한 값 |
| `CORS_ALLOWED_ORIGINS` | 브라우저 REST 요청을 허용할 프론트엔드 origin 목록 |
| `WS_ALLOWED_ORIGINS` | WebSocket handshake를 허용할 프론트엔드 origin 목록 |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka bootstrap 주소 목록 |

여러 origin은 쉼표로 구분합니다. 실제 비밀 값은 저장소나 이미지에 포함하지 않고 배포 환경의 Secret으로 주입합니다.

`JWT_ACCESS_TOKEN_EXPIRATION`은 선택 값이며, 지정하지 않으면 애플리케이션 기본값 `30m`을 사용합니다.

`KAFKA_TOPIC_VERIFY`는 선택 값이며 기본값은 `false`입니다. `true`로 두면 기동 시 필요한 토픽과 DLT가 있는지 확인하고 없으면 기동을 실패시킵니다. 애플리케이션 기동이 Kafka 가용성에 묶이므로, 브로커가 보장되는 환경에서만 켭니다. 운영 토픽은 애플리케이션이 만들지 않으므로(`KAFKA_TOPIC_AUTO_CREATE` 기본 동작과 무관하게 prod는 생성하지 않습니다) 배포 전에 다음 토픽을 준비합니다.

```text
mopl.follow.events        mopl.follow.events.DLT
mopl.playlist.events      mopl.playlist.events.DLT
mopl.premiere.events      mopl.premiere.events.DLT
```

## DLT 조회와 수동 replay

소비 실패는 공통 오류 처리가 재시도를 모두 소진한 뒤 `<원본 토픽>.DLT`로 옮깁니다. 옮겨진 레코드는 자동으로 다시 처리되지 않습니다. 원인을 확인한 뒤 사람이 다시 넣습니다.

`DeadLetterReplayService`가 그 경계입니다.

| 메서드 | 하는 일 |
| --- | --- |
| `find(deadLetterTopic, limit)` | DLT 레코드를 오래된 순으로 조회합니다. 원본 토픽, 파티션 키, eventId, 실패 원인과 적재 시각을 확인할 수 있습니다 |
| `replay(deadLetterTopic, partition, offset)` | 좌표로 지목한 레코드 한 건을 원본 토픽으로 다시 보냅니다 |

절차는 다음과 같습니다.

1. `mopl_kafka_dlt_records_total`이 늘어난 토픽을 확인합니다.
2. `find`로 실패 원인과 대상 레코드의 `partition`, `offset`을 확인합니다.
3. 원인을 해소합니다. 원인이 남아 있으면 다시 보내도 같은 실패를 반복합니다.
4. `replay`로 레코드를 하나씩 다시 보냅니다.

주의할 점이 있습니다.

- replay는 좌표로 지목한 한 건만 보냅니다. DLT 전체를 한 번에 되돌리는 경로는 두지 않았습니다. 원인을 확인하지 않은 레코드까지 함께 나가면 같은 실패가 반복되고 DLT만 늘어납니다.
- 값과 키를 원본 바이트 그대로 보냅니다. eventId와 파티션 키가 유지되므로 이미 처리에 성공한 이벤트를 다시 보내도 소비자의 멱등 경계가 걸러냅니다.
- replay는 DLT 레코드를 지우지 않습니다. 실패 이력이 남아 있어야 무엇을 언제 다시 보냈는지 확인할 수 있습니다. 같은 레코드가 조회에 계속 나오는 것은 정상입니다.
- 발행 대상은 공통 계약의 토픽으로 제한합니다. 원본 토픽은 DLT 레코드의 헤더에서 읽는데, 그 값을 그대로 믿고 발행하면 계약 밖의 토픽으로 나갈 수 있습니다.
- 조회는 그때마다 새 Consumer Group으로 읽고 offset을 커밋하지 않습니다. 도메인 리스너의 소비 위치에 영향을 주지 않습니다.

`KAFKA_DLT_REPLAY_ACK_TIMEOUT`은 선택 값이며 기본값은 `10s`입니다. 이 시간 안에 원본 토픽 발행 확인을 받지 못하면 replay는 실패로 끝나고 DLT 레코드는 그대로 남습니다.

## 실행 예시

PostgreSQL과 Redis가 같은 Docker 네트워크에서 각각 `mopl-postgres`, `mopl-redis`라는 이름으로 실행 중인 경우 다음과 같이 기동할 수 있습니다.

```bash
docker run --rm \
  --network <docker-network> \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://mopl-postgres:5432/mopl \
  -e SPRING_DATASOURCE_USERNAME=<database-user> \
  -e SPRING_DATASOURCE_PASSWORD=<database-password> \
  -e SPRING_DATA_REDIS_HOST=mopl-redis \
  -e SPRING_DATA_REDIS_PORT=6379 \
  -e JWT_SECRET=<base64-secret> \
  -e CORS_ALLOWED_ORIGINS=<frontend-origin> \
  -e WS_ALLOWED_ORIGINS=<frontend-origin> \
  -e KAFKA_BOOTSTRAP_SERVERS=mopl-kafka:9092 \
  mopl:local
```

기동 후 다음 요청이 성공해야 합니다.

```bash
curl --fail http://localhost:8080/actuator/health
```

정상 응답은 `{"status":"UP"}`이며, Docker 컨테이너 상태도 `healthy`로 전환되어야 합니다.

## CI 컨테이너 smoke 검증

`develop` 또는 `main` 대상 PR과 두 브랜치의 push에서는 Gradle 빌드·테스트가
통과한 뒤 운영 이미지 smoke 검증을 실행합니다.

CI는 PostgreSQL 16과 Redis 7을 준비하고 다음 항목을 자동으로 확인합니다.

- 저장소의 Dockerfile로 운영 이미지를 빌드할 수 있다.
- 실행 이미지의 기본 사용자가 비특권 사용자 `mopl`이다.
- 빈 PostgreSQL에 Flyway 마이그레이션을 적용하고 Redis에 연결한다.
- prod 프로파일 애플리케이션의 Docker health status가 `healthy`가 된다.
- `/actuator/health`가 `UP` 상태를 반환한다.

이 단계에서 사용하는 DB 자격값과 JWT Secret은 격리된 CI 실행에서만 쓰는 테스트
값입니다. 이미지를 레지스트리에 게시하거나 실제 운영 환경에 배포하지 않습니다.

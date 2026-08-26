# MOPL 애플리케이션 이미지 실행

이 문서는 Spring Boot 애플리케이션 이미지를 빌드하고 `prod` 프로파일로 실행하는 데 필요한 계약을 정리합니다. 실제 배포 자동화는 별도 작업에서 다룹니다.

외부 HTTPS 진입점, 동일 origin 라우팅, 백엔드 2인스턴스, 내부 네트워크, 영속성, Secret과 복구 책임의 확정 기준은 [ADR-009: MOPL 1차 배포 토폴로지와 운영 경계](docs/19-deployment-topology-adr.md)를 따릅니다.

## 이미지 빌드

```bash
docker build --pull -t mopl:local .
```

Dockerfile은 빌드 단계와 실행 단계를 분리합니다. 실행 이미지는 `mopl` 비특권 사용자로 애플리케이션을 실행하며 `/actuator/health/liveness`를 Docker healthcheck로 사용합니다.

## 운영 환경 변수

`prod` 프로파일이 쓰는 값을 세 범주로 나눕니다.

- **필수** — 없으면 기동이 실패합니다. 기본값을 두지 않았습니다.
- **Secret** — 필수이면서 값 자체가 비밀입니다. 저장소, 이미지, 로그에 남기지 않습니다.
- **조정값** — 기본값으로 동작합니다. 운영 중 필요할 때만 바꿉니다.

기동에 필요한 값이 빠지면 애플리케이션이 뜨지 않습니다. 형식이 잘못된 값은 `ProdEnvironmentValidator`가 기동 시점에 걸러 내며, 문제가 여럿이면 한 번에 모두 보고합니다. 하나씩 알려 주면 고치고 다시 띄우기를 값의 수만큼 반복해야 하고 그 반복이 그대로 중단 시간이 됩니다.

### 필수

| 변수 | 설명 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | 운영 실행 시 `prod` |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL 사용자 |
| `SPRING_DATA_REDIS_HOST` | Redis 호스트 |
| `SPRING_DATA_REDIS_PORT` | Redis 포트 |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka bootstrap 주소 목록 |
| `ELASTICSEARCH_URIS` | Elasticsearch 접속 URI |
| `MAIL_HOST` | 운영 SMTP 호스트 |
| `MAIL_PORT` | 운영 SMTP 포트 |
| `CORS_ALLOWED_ORIGINS` | 브라우저 REST 요청을 허용할 origin 목록 |
| `WS_ALLOWED_ORIGINS` | WebSocket handshake를 허용할 origin 목록 |
| `OAUTH2_SUCCESS_REDIRECT_URI` | OAuth 인증 성공 후 이동할 프론트엔드 Callback 절대 URI |
| `OAUTH2_FAILURE_REDIRECT_URI` | OAuth 인증 실패 후 이동할 프론트엔드 로그인 절대 URI |
| `GOOGLE_OAUTH_REDIRECT_URI` | Google Console에 승인된 운영용 Callback 절대 URI |
| `KAKAO_OAUTH_REDIRECT_URI` | Kakao Developers에 등록한 운영용 Callback 절대 URI |
| `NAVER_OAUTH_REDIRECT_URI` | Naver Developers에 등록한 운영용 Callback 절대 URI |
| `IMAGE_STORAGE_BUCKET` | 이미지 S3 버킷 이름 |
| `IMAGE_STORAGE_PUBLIC_BASE_URL` | 이미지 조회 URL의 앞부분. CDN을 두면 그 주소 |

origin 목록은 쉼표로 구분합니다. origin은 scheme과 host까지이며 경로를 붙이지 않습니다. 경로가 붙으면 어떤 요청과도 맞지 않아 설정은 있는데 모든 브라우저 요청이 막힙니다.

`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATA_REDIS_*`는 `application.yml`에 나타나지 않습니다. Spring Boot가 환경 변수 이름을 그대로 읽습니다.

### Secret

| 변수 | 설명 |
| --- | --- |
| `SPRING_DATASOURCE_PASSWORD` | PostgreSQL 비밀번호 |
| `JWT_SECRET` | HS256용 32바이트 이상 키를 Base64로 인코딩한 값 |
| `OAUTH2_LOCAL_CREDENTIAL_VERIFICATION_SECRET` | OAuth 사용자의 이메일 인증 코드를 HMAC-SHA256으로 보호하는 32자 이상의 비밀 값 |
| `GOOGLE_OAUTH_CLIENT_ID` | Google Cloud Console에서 발급한 웹 애플리케이션 OAuth Client ID |
| `GOOGLE_OAUTH_CLIENT_SECRET` | Google Cloud Console에서 발급한 OAuth Client Secret |
| `KAKAO_OAUTH_CLIENT_ID` | Kakao Developers에서 발급한 REST API 키 |
| `KAKAO_OAUTH_CLIENT_SECRET` | Kakao Developers에서 발급하고 활성화한 Client Secret |
| `NAVER_OAUTH_CLIENT_ID` | Naver Developers에서 발급한 Client ID |
| `NAVER_OAUTH_CLIENT_SECRET` | Naver Developers에서 발급한 Client Secret |
| `TMDB_ACCESS_TOKEN` | TMDB API 접근 토큰 |
| `SPORTSDB_API_KEY` | SportsDB API 키 |
| `MAIL_USERNAME` | SMTP 인증 사용자. 인증이 필요한 SMTP에서만 |
| `MAIL_PASSWORD` | SMTP 인증 비밀번호. 인증이 필요한 SMTP에서만 |

`JWT_SECRET`은 Base64로 디코딩한 길이가 32바이트 미만이면 기동이 실패합니다. 짧은 키는 HS256 서명 강도를 떨어뜨립니다.

S3 접근에는 자격 증명을 주입하지 않습니다. 기본 자격 증명 체인이 EC2 인스턴스 역할을 먼저 찾습니다.

### 조정값

지정하지 않으면 기본값으로 동작합니다. 각 값을 언제 바꾸는지는 아래 해당 절에 있습니다.

| 영역 | 변수 | 기본값 |
| --- | --- | --- |
| 인증 | `JWT_ACCESS_TOKEN_EXPIRATION` | `3h` |
| 인증 | `REFRESH_TOKEN_EXPIRATION` | `7d` |
| 인증 | `REFRESH_TOKEN_COOKIE_SAME_SITE` | `Lax` |
| 인증 | `REFRESH_TOKEN_COOKIE_SECURE` | `true` (prod) |
| 인증 | `OAUTH2_AUTHORIZATION_REQUEST_TTL` | `5m` |
| 인증 | `OAUTH2_USER_INFO_CONNECT_TIMEOUT`, `OAUTH2_USER_INFO_READ_TIMEOUT` | `3s`, `5s` |
| 인증 | `OAUTH2_LINK_INTENT_EXPIRATION` | `5m` |
| 인증 | `OAUTH2_LOCAL_CREDENTIAL_VERIFICATION_EXPIRATION` | `10m` |
| 인증 | `OAUTH2_LOCAL_CREDENTIAL_RESEND_COOLDOWN` | `1m` |
| 인증 | `OAUTH2_LOCAL_CREDENTIAL_MAX_ATTEMPTS` | `5` |
| 메일 | `MAIL_SMTP_AUTH` | `false` |
| 메일 | `MAIL_STARTTLS_ENABLE` | `false` |
| 메일 | `MAIL_CONNECTION_TIMEOUT`, `MAIL_READ_TIMEOUT`, `MAIL_WRITE_TIMEOUT` | `5000` |
| 메일 | `PASSWORD_RESET_MAIL_FROM` | `no-reply@mopl.local` |
| 메일 | `PASSWORD_RESET_MAIL_SUBJECT` | `[모두의 플리] 임시 비밀번호 안내` |
| 메일 | `OAUTH2_LOCAL_CREDENTIAL_MAIL_FROM` | `no-reply@mopl.local` |
| 메일 | `OAUTH2_LOCAL_CREDENTIAL_MAIL_SUBJECT` | `[모두의 플리] 이메일 인증 코드 안내` |
| 이미지 | `IMAGE_STORAGE_ENABLED` | `true` (prod) |
| 이미지 | `IMAGE_STORAGE_REGION` | `ap-northeast-2` |
| 이미지 | `IMAGE_STORAGE_PROFILE_PREFIX` | `profile-images` |
| 이미지 | `IMAGE_STORAGE_THUMBNAIL_PREFIX` | `thumbnails` |
| 이미지 | `IMAGE_STORAGE_MAX_FILE_SIZE` | `5242880` |
| Kafka | `KAFKA_TOPIC_VERIFY` | `false` |
| Kafka | `KAFKA_LISTENER_AUTO_STARTUP` | `true` |
| Kafka | `KAFKA_DLT_REPLAY_ACK_TIMEOUT` | `10s` |
| Outbox | `OUTBOX_RELAY_*`, `OUTBOX_RETRY_*`, `OUTBOX_LEASE_DURATION` | 아래 Outbox relay 조정 값 |
| Outbox | `OUTBOX_CLEANUP_*` | 아래 발행 완료 레코드 정리 |
| Outbox | `OUTBOX_METRICS_ENABLED`, `OUTBOX_METRICS_REFRESH_INTERVAL` | `true`, `15000` |
| Kafka 소비 | `PROCESSED_EVENT_CLEANUP_*` | 아래 멱등 처리 기록 정리 |
| 실시간 | `REALTIME_RELAY_ENABLED` | `true` |
| 실시간 | `REALTIME_RELAY_SUBSCRIBE_RETRY_INTERVAL` | `30000` |
| DM | `DIRECT_MESSAGE_PRESENCE_TTL`, `DIRECT_MESSAGE_PRESENCE_RENEW_INTERVAL` | `30s`, `10s` |
| DM | `DIRECT_MESSAGE_RATE_LIMIT_MAX_MESSAGES`, `DIRECT_MESSAGE_RATE_LIMIT_WINDOW` | `10`, `5s` |
| 시청 세션 | `WATCHING_SESSION_*` | `application.yml` 참고 |

`KAFKA_TOPIC_AUTO_CREATE`는 `prod`에서 `false`로 고정되어 있어 환경 변수로 켤 수 없습니다. 운영 토픽은 파티션 수 결정이 코드 배포에 묶이지 않도록 미리 준비합니다.

### 운영에서 기본값을 그대로 두면 안 되는 값

`prod` 프로파일이 아래 값을 개발용 기본값에서 떼어 냈습니다. 예전에는 값을 주지 않아도 기동했고, 그 사실이 사용자 신고로만 드러났습니다.

- `MAIL_HOST`, `MAIL_PORT` — 기본 문서의 `localhost:1025`는 로컬 Mailpit 주소입니다. 그대로 두면 비밀번호 초기화 메일이 조용히 실패하고 전체 health도 계속 `DOWN`입니다.
- `IMAGE_STORAGE_BUCKET`, `IMAGE_STORAGE_PUBLIC_BASE_URL` — `IMAGE_STORAGE_ENABLED`가 `prod`에서 `true`가 기본이므로 이 둘이 필요합니다. 비어 있으면 기동이 실패합니다. 켜 두고 비워 두면 업로드 시점에야 드러나는데, 그때는 이미 사용자가 파일을 고른 뒤입니다.

### 배포 전에 준비할 Kafka 토픽

운영 토픽은 애플리케이션이 만들지 않습니다. 파티션 수 결정이 코드 배포에 묶이지 않아야 하기 때문입니다. 배포 전에 다음 토픽과 DLT를 준비합니다.

```text
mopl.follow.events          mopl.follow.events.DLT
mopl.playlist.events        mopl.playlist.events.DLT
mopl.premiere.events        mopl.premiere.events.DLT
mopl.direct-message.events  mopl.direct-message.events.DLT
```

`KAFKA_TOPIC_VERIFY=true`로 두면 기동 시 이 토픽이 있는지 확인하고 없으면 기동을 실패시킵니다. 애플리케이션 기동이 Kafka 가용성에 묶이므로 브로커가 보장되는 환경에서만 켭니다. DLT가 없으면 DLT 발행이 실패하면서 원본 처리까지 막히므로, 기동 시점에 드러내는 편이 낫습니다.

### Secret 주입

실제 값은 저장소, 이미지, 로그에 남기지 않습니다. 서버의 환경 파일 하나에 모아 두고 Compose가 읽습니다. 파일 소유자는 배포 계정, 권한은 소유자 읽기·쓰기만 둡니다.

값을 바꾸면 컨테이너를 다시 만들어야 반영됩니다. 재시작만으로는 반영되지 않습니다.

CI smoke가 쓰는 값은 격리된 실행에서만 쓰는 테스트 값입니다. 운영에 쓰지 않습니다.

### 프로필·콘텐츠 이미지 저장

사용자가 올린 이미지는 S3에 저장합니다. 애플리케이션 컨테이너의 로컬 파일에 쓰지 않습니다. 인스턴스가 둘이면 A가 저장한 파일을 B가 읽지 못하고, 컨테이너를 다시 만들면 사라집니다.

| 환경 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `IMAGE_STORAGE_ENABLED` | `false` | S3 사용 여부. 운영은 `true` |
| `IMAGE_STORAGE_BUCKET` | 없음 | 버킷 이름. `true`일 때 필수 |
| `IMAGE_STORAGE_REGION` | `ap-northeast-2` | 버킷 리전 |
| `IMAGE_STORAGE_PUBLIC_BASE_URL` | 없음 | 조회 URL의 앞부분. `true`일 때 필수 |
| `IMAGE_STORAGE_PROFILE_PREFIX` | `profile-images` | 프로필 이미지 객체 키 구분자 |
| `IMAGE_STORAGE_THUMBNAIL_PREFIX` | `thumbnails` | 콘텐츠 썸네일 객체 키 구분자 |
| `IMAGE_STORAGE_MAX_FILE_SIZE` | `5242880` | 허용 최대 크기(바이트) |

`IMAGE_STORAGE_ENABLED=false`로 두면 파일을 저장하지 않고 열리지 않는 주소만 돌려주는 구현이 붙습니다. AWS 자격 증명 없이 로컬 개발과 테스트를 돌리기 위한 것입니다. **운영에서 이 값을 켜지 않으면 업로드한 이미지가 조회되지 않습니다.**

#### 자격 증명

액세스 키를 환경 변수나 코드에 두지 않습니다. 기본 자격 증명 체인이 EC2 인스턴스 역할을 먼저 찾으므로, 서버에 장기 자격 증명이 남지 않습니다.

인스턴스 역할에 필요한 권한은 업로드 하나뿐입니다.

```json
{
  "Effect": "Allow",
  "Action": ["s3:PutObject"],
  "Resource": "arn:aws:s3:::<버킷>/*"
}
```

조회는 애플리케이션을 거치지 않습니다. 버킷 정책이나 앞단 CDN이 공개 읽기를 담당하므로 `s3:GetObject`는 인스턴스 역할에 주지 않습니다.

#### 검증과 객체 키

- 허용 형식은 `image/jpeg`, `image/png`, `image/webp`, `image/gif`입니다. 형식과 크기를 확인한 뒤에만 올립니다.
- 객체 키에 원본 파일명을 쓰지 않습니다. 두 사용자가 같은 이름을 올리면 뒤가 앞을 덮고, 이름에 경로 기호가 섞이면 의도한 구분자 밖으로 나갑니다.
- 확장자는 파일명이 아니라 Content-Type에서 정합니다. 파일명의 확장자는 내용과 무관하게 붙일 수 있습니다.
- 업로드가 실패하면 예외로 끊습니다. 조용히 넘기면 이미지 없는 레코드가 저장되고 사용자에게는 성공으로 보입니다.

#### 이전 객체 정리

**교체하거나 지운 이미지의 이전 객체를 지우지 않습니다.** 프로필 이미지를 바꾸면 이전 객체가 버킷에 남습니다.

1차 배포 범위에서 제외한 이유는 지우는 시점을 정하려면 그 URL을 아무도 참조하지 않는다는 보장이 필요한데, 지금은 사용자와 콘텐츠 레코드가 URL 문자열만 들고 있어 역참조가 없기 때문입니다. 트랜잭션이 롤백되면 이미 지운 객체를 되돌릴 수도 없습니다.

당장은 버킷 수명 주기 규칙으로 오래된 객체를 정리하고, 참조를 추적하는 정리 경로는 #351에서 다룹니다.

#### 버킷 설정

운영 버킷은 `sb11-mopl-team1-images`입니다. 버전 관리와 SSE-S3 암호화를 켰습니다.

수명 주기 규칙은 `deploy/aws/s3-lifecycle.json`에 있습니다.

```bash
aws s3api put-bucket-lifecycle-configuration --bucket sb11-mopl-team1-images --lifecycle-configuration file://deploy/aws/s3-lifecycle.json
```

| 규칙 | 대상 | 기간 |
| --- | --- | --- |
| `expire-noncurrent-versions` | 이전 버전 | 30일 |
| `expire-noncurrent-versions` | 미완료 멀티파트 업로드 | 7일 |
| `expire-delete-markers` | 만료된 삭제 마커 | 즉시 |

버전 관리를 켠 이상 이 규칙이 없으면 안 됩니다. 앱이 이미지를 지워도 삭제 마커만 쌓이고 이전 버전은 영원히 남아, 프로필 사진을 자주 바꾸는 사용자마다 저장 용량이 계속 늘어납니다. 위의 "이전 객체 정리"가 지우지 않는 것을 여기서 받습니다.

퍼블릭 액세스 차단은 ACL 경로만 켭니다. 정책 경로는 열어 두고 버킷 정책이 `s3:GetObject`만 허용합니다. 조회가 애플리케이션을 거치지 않기 때문입니다. ACL 경로를 막아 두면 객체 하나를 실수로 공개 ACL로 올리는 일 자체가 생기지 않고, 공개 경로가 버킷 정책 한 곳으로 모입니다.

| 설정 | 값 |
| --- | --- |
| `BlockPublicAcls` | `true` |
| `IgnorePublicAcls` | `true` |
| `BlockPublicPolicy` | `false` |
| `RestrictPublicBuckets` | `false` |

### OAuth2 로그인과 세션 고정

**로드밸런서 세션 고정을 쓰지 않습니다.** 백엔드 인스턴스가 몇 개든, 어느 인스턴스로 요청이 가든 소셜 로그인이 동작해야 합니다.

OAuth2 로그인은 두 번의 요청으로 나뉩니다. 인가를 시작하는 `/oauth2/authorization/{provider}`와 Provider가 돌려보내는 `/login/oauth2/code/{provider}`입니다. 그 사이에 사용자는 Provider 화면에 다녀오므로 두 요청이 같은 인스턴스로 간다는 보장이 없습니다.

Spring Security 기본 구현은 그 사이의 상태를 HTTP 세션에 둡니다. 세션은 인스턴스 로컬이라 인가를 시작한 인스턴스와 callback을 받은 인스턴스가 다르면 저장한 요청을 찾지 못하고 로그인이 실패합니다. 백엔드가 둘이면 절반 확률로 그렇게 됩니다.

그래서 인가 요청을 Redis에 두고 모든 인스턴스가 함께 봅니다.

| 환경 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `OAUTH2_AUTHORIZATION_REQUEST_TTL` | `5m` | 인가 요청을 Redis에 두는 기간 |

운영에서 알아둘 점이 있습니다.

- Redis가 없으면 소셜 로그인이 동작하지 않습니다. 이메일·비밀번호 로그인은 영향을 받지 않습니다.
- 키는 `auth:oauth2:authorization-request:{state}`입니다. `state`는 Spring Security가 만들어 Provider로 보내고 callback에 그대로 돌아오는 값입니다.
- 저장하는 값에 client secret이나 access token은 들어가지 않습니다. 인가 요청을 다시 만들기 위한 값과 PKCE code verifier만 들어갑니다. code verifier는 서버에만 있어야 하는 값이라 클라이언트가 아니라 Redis에 둡니다.
- callback에서 값을 꺼낼 때 `GETDEL`로 읽으면서 지웁니다. 같은 `state`로 두 번째 요청이 오면 찾지 못하고 실패합니다. 인가 코드 재사용 시도가 여기서 끊깁니다.
- 사용자가 Provider 화면에서 로그인을 끝내지 않고 떠나면 그 요청은 소비되지 않습니다. `OAUTH2_AUTHORIZATION_REQUEST_TTL`이 지나면 사라집니다. 이 값을 늘리면 소비되지 않은 요청이 그만큼 오래 남습니다.
- `state`가 없거나, 모르는 값이거나, 만료됐거나, 이미 소비된 요청은 모두 인증 실패입니다. Spring Security가 `authorization_request_not_found`로 처리하고 `OAUTH2_FAILURE_REDIRECT_URI`로 보냅니다.

### 프록시 뒤에서의 host와 scheme

`prod` 프로파일은 `server.forward-headers-strategy: framework`를 켭니다. 운영에서는 백엔드가 Caddy와 프론트엔드 Nginx 뒤에 있으므로, 이 설정이 없으면 백엔드가 자기 주소를 내부 `http` 주소로 인식합니다.

**이 설정은 `prod`에서만 켭니다.** 프록시가 앞에 없는 환경에서 켜면 클라이언트가 직접 보낸 `X-Forwarded-*`를 그대로 믿게 됩니다.

Gateway는 `X-Forwarded-Proto`를 내부 연결 scheme인 `http`로 덮어쓰지 않아야 합니다. 덮어쓰면 백엔드가 외부 요청을 평문으로 인식합니다.

다만 OAuth Callback URI는 이 추론에 기대지 않습니다. `GOOGLE_OAUTH_REDIRECT_URI` 같은 값으로 외부 HTTPS 절대 URI를 직접 받습니다. Provider Console에 등록한 값과 이 환경 변수가 같아야 합니다.

### Outbox relay 조정 값

도메인 상태 변경과 함께 기록된 이벤트는 Outbox relay가 읽어 Kafka에 발행합니다. 아래 값은 모두 선택이며, 지정하지 않으면 기본값으로 동작합니다.

| 환경 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `OUTBOX_RELAY_ENABLED` | `true` | relay 주기 실행 여부. 브로커 없이 기동해야 하는 환경에서만 끕니다 |
| `OUTBOX_RELAY_INTERVAL` | `1000` | 이전 실행이 끝난 뒤 다음 실행까지의 간격(밀리초) |
| `OUTBOX_RELAY_BATCH_SIZE` | `100` | 한 주기에 선점해 발행할 최대 건수 |
| `OUTBOX_RELAY_ACK_TIMEOUT` | `10s` | broker 발행 확인을 기다리는 한도 |
| `OUTBOX_LEASE_DURATION` | `30s` | 선점 유효 기간 |
| `OUTBOX_RETRY_MAX_ATTEMPTS` | `10` | 이 횟수까지 실패하면 자동 재시도를 멈춥니다 |
| `OUTBOX_RETRY_INITIAL_BACKOFF` | `5s` | 첫 실패 뒤 기다리는 시간 |
| `OUTBOX_RETRY_MAX_BACKOFF` | `10m` | 재시도 간격 상한 |
| `OUTBOX_RETRY_MULTIPLIER` | `2.0` | 실패할 때마다 간격에 곱하는 값 |

`OUTBOX_LEASE_DURATION`이 짧으면 발행이 끝나기 전에 다른 인스턴스가 회수해 중복 발행이 늘고, 길면 relay가 비정상 종료했을 때 회수가 그만큼 지연됩니다. `OUTBOX_RELAY_ACK_TIMEOUT`보다 넉넉하게 둡니다.

최대 시도 횟수를 넘긴 이벤트는 삭제하지 않고 최종 실패 상태로 남습니다. 자동 relay 대상에서는 빠지므로, 원인을 고친 뒤 아래 관리자 API로 다시 발행 대기로 돌립니다.

### 발행 완료 레코드 정리

`outbox_events`는 도메인 사건마다 한 행이 늘고, 발행에 성공해도 상태만 바뀝니다. 지우는 경로가 없으면 저장 공간과 함께 백업, 마이그레이션 비용이 계속 커집니다. 보관 기간을 지난 발행 완료 레코드를 주기적으로 지웁니다.

| 환경 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `OUTBOX_CLEANUP_ENABLED` | `true` | 정리 주기 실행 여부 |
| `OUTBOX_CLEANUP_INTERVAL` | `3600000` | 이전 실행이 끝난 뒤 다음 실행까지의 간격(밀리초) |
| `OUTBOX_CLEANUP_RETENTION` | `7d` | 발행을 마친 뒤 이 기간이 지나야 지웁니다 |
| `OUTBOX_CLEANUP_BATCH_SIZE` | `1000` | 한 번의 실행이 지울 최대 건수 |

발행 대기와 최종 실패 레코드는 보관 기간과 무관하게 남습니다. 아직 나가지 않았거나 사람이 처리해야 할 이벤트입니다.

보관 기간을 두는 이유는 발행 직후 삭제가 안전하지 않기 때문입니다. 발행 확인을 받고 상태를 반영하기 전에 프로세스가 종료되면 같은 이벤트가 다시 발행되는데, 그 흔적이 남아 있어야 무슨 일이 있었는지 확인할 수 있습니다. 소비 지연이나 재처리 요청이 들어오는 기간을 감당할 만큼 잡습니다.

삭제 상한을 두는 이유는 한 번의 실행이 오래 걸리면 그동안 잠금과 트랜잭션이 유지되어 relay의 선점과 도메인 트랜잭션이 함께 느려지기 때문입니다. 상한에 걸려 남은 레코드는 다음 실행으로 넘어갑니다. 쌓인 양이 많아 한 주기로 따라잡지 못하면 `OUTBOX_CLEANUP_INTERVAL`을 줄이거나 상한을 올립니다.

지운 건수는 `mopl_outbox_cleaned_records_total`로 확인합니다.

### 전달 상태 확인

발행 실패는 조용히 쌓입니다. 도메인 요청은 정상 응답을 받고 커밋까지 끝나므로 이벤트가 나가지 않아도 API 지표에는 흔적이 없습니다. 아래 지표가 그 상황을 드러냅니다.

| 지표 | 종류 | 의미 |
| --- | --- | --- |
| `mopl_outbox_events{state="pending"}` | gauge | 발행을 기다리는 레코드 수 |
| `mopl_outbox_events{state="claimed"}` | gauge | relay가 선점 중인 레코드 수 |
| `mopl_outbox_events{state="failed"}` | gauge | 자동 재시도를 멈춘 레코드 수 |
| `mopl_outbox_oldest_pending_age_seconds` | gauge | 가장 오래된 대기 이벤트의 발생 후 경과 시간 |
| `mopl_outbox_relay_records_total{outcome="published"}` | counter | 발행 확인까지 마친 건수 |
| `mopl_outbox_relay_records_total{outcome="retried"}` | counter | 실패 후 다시 시도하기로 한 건수 |
| `mopl_outbox_relay_records_total{outcome="exhausted"}` | counter | 최대 시도 횟수를 넘겨 최종 실패로 남긴 건수 |
| `mopl_outbox_relay_batch_size` | summary | 한 주기에 선점한 레코드 수 |
| `mopl_outbox_relay_duration_seconds` | timer | 한 주기의 선점부터 상태 반영까지 걸린 시간 |
| `mopl_kafka_dlt_records_total{topic="..."}` | counter | DLT로 옮긴 레코드 수 |

전달이 멈췄는지는 대기 건수보다 `mopl_outbox_oldest_pending_age_seconds`로 판단합니다. 대기 건수는 유입이 늘어도 함께 커지지만, 가장 오래된 대기 이벤트의 경과 시간은 발행이 진행되는 한 낮게 유지됩니다. 이 값이 계속 커지면 relay가 멈췄거나 특정 이벤트가 반복 실패하는 중입니다.

`mopl_outbox_events{state="failed"}`가 0보다 크면 사람이 개입할 때까지 그대로 남습니다. 원인을 확인한 뒤 다시 발행 대기로 돌려야 합니다.

gauge 값은 `OUTBOX_METRICS_REFRESH_INTERVAL`(기본 15000밀리초)마다 집계합니다. 수집 시점마다 집계하지 않는 이유는 수집 주기가 그대로 데이터베이스 부하가 되기 때문입니다. `OUTBOX_METRICS_ENABLED=false`로 두면 갱신을 멈추고 값이 직전 상태에 고정됩니다.

지표는 `/actuator/prometheus`로 노출합니다. 이 경로는 인증이 필요한 경로이므로, 스크레이퍼가 접근할 방법은 배포 환경에서 별도로 정합니다.

### 최종 실패 이벤트 조회와 종결

`mopl_outbox_events{state="failed"}`가 0보다 크면 사람이 개입해야 합니다. 관리자 전용 API 세 개가 그 경계입니다. 세 경로 모두 `ROLE_ADMIN`이 없으면 403입니다.

| 메서드와 경로 | 하는 일 |
| --- | --- |
| `GET /api/admin/outbox/failures?limit=20` | 최종 실패 이벤트를 발생 시각이 이른 순으로 조회합니다. `limit`은 1에서 100 사이이며 기본값은 20입니다 |
| `POST /api/admin/outbox/failures/{eventId}/requeue` | 이벤트 한 건을 다시 발행 대기로 되돌립니다 |
| `POST /api/admin/outbox/failures/{eventId}/skip` | 이벤트 한 건을 보내지 않기로 하고 종결합니다. 본문에 `reason`이 필요합니다 |

끝내는 방법은 둘입니다. 원인을 고쳤으면 `requeue`로 다시 내보내고, 보내지 않아도 된다고 판단했으면 `skip`으로 사유를 남기고 종결합니다. 어느 쪽이든 행을 지우지 않습니다.

절차는 다음과 같습니다.

1. 목록을 조회해 `lastError`로 실패 원인과 `attempts`로 몇 번 시도했는지 확인합니다.
2. 원인을 해소합니다. 원인이 남아 있으면 되돌려도 같은 실패를 반복하고 최종 실패로 돌아옵니다.
3. `requeue`로 이벤트를 하나씩 되돌립니다. 이벤트를 보내는 것 자체가 더 이상 의미가 없다면 대신 `skip`으로 사유를 남기고 종결합니다.
4. `totalCount`가 줄어드는지 확인합니다. 목록은 상한이 걸린 조회라 남은 규모는 이 값으로 봅니다.

주의할 점이 있습니다.

- 목록에 이벤트 payload를 담지 않습니다. payload에는 DM 본문처럼 도메인이 사용자에게만 보이기로 한 값이 들어갑니다. 운영 조회가 그 경계를 우회하는 통로가 되면 안 됩니다. `lastError`도 500자까지만 싣고 전체는 애플리케이션 로그에서 확인합니다.
- 재처리는 새 레코드나 새 envelope을 만들지 않고 기존 행의 상태만 되돌립니다. eventId, 파티션 키, 중복 제거 키가 유지되므로 이미 처리에 성공한 이벤트가 다시 나가도 소비자의 멱등 경계가 걸러냅니다.
- 단건 경로만 열려 있습니다. 원인을 확인하지 않은 일괄 처리는 같은 실패와 부하를 그대로 반복합니다.
- 대상이 없으면 404, 최종 실패 상태가 아니면 409입니다. `requeue`는 같은 요청을 두 번 보내면 두 번째가 409입니다. 두 요청이 동시에 들어와도 전이는 한 번만 일어납니다.
- `skip`은 같은 요청을 두 번 보내도 204입니다. 결과가 "그 이벤트는 건너뛴 상태다"로 같기 때문입니다. 다만 처리자와 시각, 사유는 처음 전환 때의 값을 그대로 둡니다. 실제로 판단한 사람은 처음 부른 쪽입니다.
- `skip`한 이벤트는 되돌릴 수 없습니다. 종결 상태이고, 빠져나가는 전이를 두지 않았습니다. 다시 보내야 한다면 도메인에서 사건을 새로 만듭니다.
- `skip`한 행은 정리 대상이 아닙니다. 발행 완료 레코드 정리는 `PUBLISHED`만 지웁니다. 판단의 기록이 지워지면 남긴 의미가 없습니다.
- 순서를 보장하는 partition에서 앞선 이벤트를 `skip`하면 뒤 이벤트가 진행합니다. 계속 막으면 앞선 이벤트를 종결한 의미가 없고 뒤 이벤트도 함께 최종 실패로 밀려갑니다.
- 조회와 재처리, 건너뛰기는 `mopl.audit.outbox` logger로 처리자, 대상과 결과를 남깁니다. 이름을 따로 둔 이유는 이 로그의 보존 기준이 진단 로그와 다르기 때문입니다. 배포 환경에서 별도 대상으로 보내거나 더 오래 보관합니다.

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

## 리스너 중지 확인과 재시작

DLT 발행이 같은 레코드에서 세 번 연속 실패하면 공통 오류 처리가 리스너 컨테이너를 멈춥니다. 계약이 DLT 발행 실패 시 원본 offset을 성공 처리하지 못하게 하므로, 멈추지 않으면 같은 레코드를 무한히 다시 소비합니다.

그 뒤로 소비는 멈춰 있지만 프로세스는 살아 있고 REST는 정상 응답합니다. API 지표에도 흔적이 없습니다. 아래 두 가지가 그 상황을 드러냅니다.

| 확인 대상 | 값 |
| --- | --- |
| `GET /actuator/health` | 리스너가 비정상 중지되면 전체 상태가 `DOWN`이 됩니다 |
| `mopl_kafka_listener_containers{state="stopped"}` | 멈춰 있는 컨테이너 수 |
| `mopl_kafka_listener_stops_total{topic="..."}` | DLT 발행 실패로 중지한 횟수 |

`kafkaListener` health component의 상세에는 Consumer Group, 구독 토픽, 마지막 중지 시각과 사유가 들어갑니다. `/actuator/health`는 인증 없이 열려 있으므로 상세는 `ROLE_ADMIN`으로 인증한 요청에만 보입니다. 인증 없이 호출하면 상태만 보입니다.

### health 정책

리스너 중지는 liveness와 readiness 어느 group에도 넣지 않습니다.

- liveness에 넣으면 오케스트레이터가 프로세스를 재시작합니다. DLT가 아직 복구되지 않았다면 다시 띄운 리스너가 같은 이유로 또 멈춥니다. 원인은 그대로인 채 재시작만 반복됩니다.
- readiness에 넣으면 로드밸런서가 인스턴스를 뺍니다. REST 요청은 정상 처리할 수 있는 인스턴스인데 Kafka 문제로 처리 용량만 줄어듭니다.

컨테이너 HEALTHCHECK와 ALB health check는 `/actuator/health/liveness`를 씁니다. 전체 `/actuator/health`는 사람이 보거나 알림이 거는 대상입니다.

### 재시작 절차

리스너를 다시 띄우는 경로는 프로세스 재시작뿐입니다. 원인을 확인하지 않고 다시 띄우는 버튼을 두면 같은 중지가 반복되므로 API로 열지 않았습니다.

1. `/actuator/health`의 `kafkaListener` 상세에서 멈춘 Consumer Group과 사유를 확인합니다. `ROLE_ADMIN`으로 인증해야 상세가 보입니다.
2. 원인을 해소합니다. DLT 발행 실패의 원인은 대개 둘입니다.
   - 브로커에 닿지 않음. `KAFKA_BOOTSTRAP_SERVERS`와 네트워크를 확인합니다.
   - DLT 토픽이 없음. 운영 토픽은 애플리케이션이 만들지 않으므로 `<원본 토픽>.DLT`가 준비되어 있어야 합니다. `KAFKA_TOPIC_VERIFY=true`로 두면 기동 시 확인합니다.
3. 인스턴스를 재시작합니다. 리스너가 다시 붙으면 `/actuator/health`가 `UP`으로 돌아옵니다.
4. 멈춰 있는 동안 쌓인 lag을 확인합니다. offset은 성공한 지점까지만 전진했으므로 중지 시점의 레코드부터 다시 소비합니다. 소비자 멱등 경계가 중복 처리를 걸러냅니다.

원인을 해소하지 않고 재시작하면 같은 레코드에서 다시 세 번 실패하고 또 멈춥니다. `mopl_kafka_listener_stops_total`이 계속 올라가는 것이 그 신호입니다.

## 멱등 처리 기록 정리

Consumer는 이미 처리한 이벤트를 `processed_events`에 남겨 두고, 같은 `(consumer_name, event_id)`가 다시 오면 걸러냅니다. 이 테이블은 이벤트를 처리할 때마다 한 행이 늘고 갱신되지 않습니다. 지우는 경로가 없으면 테이블과 인덱스가 소비량에 비례해 계속 커집니다. 보관 기간을 지난 기록을 주기적으로 지웁니다.

| 환경 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `PROCESSED_EVENT_CLEANUP_ENABLED` | `true` | 정리 주기 실행 여부 |
| `PROCESSED_EVENT_CLEANUP_INTERVAL` | `3600000` | 이전 실행이 끝난 뒤 다음 실행까지의 간격(밀리초) |
| `PROCESSED_EVENT_CLEANUP_RETENTION` | `30d` | 기록한 뒤 이 기간이 지나야 지웁니다 |
| `PROCESSED_EVENT_CLEANUP_BATCH_SIZE` | `1000` | 한 번의 실행이 지울 최대 건수 |

**보관 기간을 줄일 때는 근거가 필요합니다.** 기록을 지운 이벤트가 다시 들어오면 처음 보는 이벤트로 판정되어 도메인 부수 효과가 한 번 더 일어납니다. 알림이 두 번 가거나 집계가 두 번 오르는 식입니다. 같은 이벤트가 다시 도착할 수 있는 경로는 둘입니다.

- Kafka 원본 토픽의 보관 기간. 그 안에서는 offset을 되돌리면 같은 레코드가 다시 소비됩니다.
- DLT 수동 replay. `DeadLetterReplayService.replay`는 원본 바이트를 그대로 보내므로 eventId가 유지됩니다. DLT 레코드를 지우지 않으므로 사람이 언제든 다시 보낼 수 있습니다.

보관 기간은 이 둘보다 길어야 합니다. 기본값 `30d`는 Kafka 기본 보관 기간 7일과 DLT를 살펴보고 replay를 결정하기까지의 여유를 함께 감당하는 값입니다. Kafka 토픽 보관 기간을 늘렸다면 이 값도 함께 늘립니다.

삭제 상한을 두는 이유는 한 번의 실행이 오래 걸리면 그동안 잠금과 트랜잭션이 유지되어 소비 경로의 기록 선점이 함께 느려지기 때문입니다. 상한에 걸려 남은 기록은 다음 실행으로 넘어갑니다. 쌓인 양이 많아 한 주기로 따라잡지 못하면 `PROCESSED_EVENT_CLEANUP_INTERVAL`을 줄이거나 상한을 올립니다.

지운 건수는 `mopl_kafka_processed_cleaned_records_total`로 확인합니다.

## 인스턴스 간 실시간 중계

WebSocket과 SSE 연결은 인스턴스마다 따로 유지됩니다. 인스턴스를 여러 개 띄우면 한 인스턴스가 만든 알림이 다른 인스턴스에 연결된 사용자에게 닿지 않습니다. Redis Pub/Sub 채널 `mopl.realtime.messages`가 그 사이를 잇습니다.

| 환경 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `REALTIME_RELAY_ENABLED` | `true` | 중계 구독 여부. Redis 없이 기동해야 하는 환경에서만 끕니다 |
| `REALTIME_RELAY_SUBSCRIBE_RETRY_INTERVAL` | `30000` | 구독이 붙지 않았을 때 다시 시도하는 간격(밀리초) |

운영에서 알아둘 점이 있습니다.

- 구독 시작에 실패해도 애플리케이션은 기동합니다. 실시간 중계는 부가 경로이므로 Redis가 준비되지 않았다고 REST와 도메인 기능까지 세우지 않습니다. 대신 구독이 붙을 때까지 위 간격으로 다시 시도하며, 그동안 다른 인스턴스의 메시지는 받지 못합니다.
- 발행 실패도 호출부로 전파하지 않습니다. Redis 연결이 끊겼다는 이유로 이미 성공한 도메인 변경이 롤백되면 안 됩니다. 실패는 경고 로그로 남습니다.
- 인스턴스 식별자는 프로세스마다 새로 만듭니다. 자기가 발행한 메시지를 되받아 다시 전달하는 것을 이 값으로 막습니다.

### 중계 상태 확인

구독 실패와 발행 실패는 어느 쪽도 호출부로 전파되지 않습니다. 도메인 요청은 정상 응답하고 커밋까지 끝나므로 API 지표에는 흔적이 없고, 다른 인스턴스에 연결된 사용자만 조용히 메시지를 받지 못합니다. 아래가 그 상황을 드러내는 신호입니다.

| 지표 | 종류 | 의미 |
| --- | --- | --- |
| `mopl_realtime_relay_subscribed` | gauge | 채널을 실제로 구독 중이면 1 |
| `mopl_realtime_relay_last_received_age_seconds` | gauge | 마지막으로 다른 인스턴스 메시지를 전달한 뒤 지난 시간. 아직 없으면 -1 |
| `mopl_realtime_relay_published_messages_total{outcome="succeeded"}` | counter | 내보낸 메시지 수 |
| `mopl_realtime_relay_published_messages_total{outcome="failed"}` | counter | 내보내지 못하고 건너뛴 메시지 수 |
| `mopl_realtime_relay_delivered_messages_total` | counter | 받아서 목적지 handler로 넘긴 메시지 수 |
| `mopl_realtime_relay_discarded_messages_total{reason="..."}` | counter | 받았지만 전달하지 않고 버린 메시지 수 |
| `mopl_realtime_relay_handler_failures_total{handler="..."}` | counter | 목적지 handler가 전달에 실패한 수 |

판정 기준은 다음과 같습니다.

- `mopl_realtime_relay_subscribed`가 0이면 그 인스턴스는 다른 인스턴스의 메시지를 받지 못합니다. 구독이 붙을 때까지 자동으로 다시 시도하므로 짧게 0이었다가 돌아오는 것은 정상입니다. 계속 0으로 남으면 Redis 연결을 확인합니다.
- `mopl_realtime_relay_published_messages_total{outcome="failed"}`가 올라가면 Redis에 내보내지 못한 것입니다. 그동안의 알림은 다른 인스턴스에 닿지 않았고, 되돌릴 경로는 없습니다.
- 폐기 이유는 둘로 나눠 봅니다. `self`와 `duplicate`는 설계대로 동작하고 있다는 뜻이라 늘 올라갑니다. `malformed`와 `incomplete`는 발행 쪽이나 채널을 지나는 계약이 깨졌다는 뜻이라 0이어야 합니다.
- `mopl_realtime_relay_handler_failures_total`은 중계는 정상인데 목적지 전달이 막혔다는 뜻입니다. `handler` 태그가 어느 도메인인지 알려줍니다.
- 구독은 정상인데 `mopl_realtime_relay_last_received_age_seconds`만 계속 커지면 상대 인스턴스가 발행을 멈췄거나 채널이 갈린 것입니다. 인스턴스가 하나뿐인 환경에서는 받을 메시지가 없으므로 이 값이 커지는 것이 정상입니다.

`/actuator/health`의 `realtimeRelay` component가 같은 상태를 보여줍니다. 구독이 붙지 않으면 `DOWN`이고, 상세에 채널, 인스턴스 식별자와 재시도 중인지가 들어갑니다. Kafka 리스너와 같은 이유로 liveness와 readiness 어느 group에도 넣지 않습니다. Redis가 복구되지 않은 채 재시작만 반복되거나, REST를 처리할 수 있는 인스턴스가 로드밸런서에서 빠지는 것을 피하기 위해서입니다.

### 두 인스턴스 중계 smoke

인스턴스가 하나면 중계가 깨져도 아무도 알아차리지 못합니다. 자기 연결로 보내는 경로는 중계를 지나지 않기 때문입니다. 실제 애플리케이션 두 개를 같은 Redis로 띄워 중계가 프로세스 사이에서 동작하는지 확인합니다.

CI의 `Container smoke` job이 운영 이미지로 두 번째 인스턴스 `mopl-ci-b`를 8081 포트에 띄우고 다음을 확인합니다.

- 두 인스턴스가 같은 채널 `mopl.realtime.messages`를 구독한다 (`PUBSUB NUMSUB`가 2)
- 각 인스턴스의 `mopl_realtime_relay_subscribed`가 1이다
- 채널에 들어온 메시지를 두 인스턴스가 각각 한 번씩 목적지 handler로 넘긴다
- 같은 `messageId`를 두 번 보내도 전달은 한 번이고 두 번째는 `duplicate`로 버려진다
- 한 인스턴스를 내려도 남은 인스턴스가 REST와 자기 구독을 유지한다
- 구독 연결이 끊겨도 다시 붙고 전달이 이어진다

로컬에서 재현하려면 PostgreSQL과 Redis를 띄운 뒤 같은 이미지를 포트만 바꿔 두 번 실행합니다.

```bash
docker build --tag mopl:local .
docker run --detach --name mopl-a --network host --env SERVER_PORT=8080 ... mopl:local
docker run --detach --name mopl-b --network host --env SERVER_PORT=8081 ... mopl:local
docker run --rm --network host redis:7 redis-cli -h 127.0.0.1 PUBSUB NUMSUB mopl.realtime.messages
```

`...` 자리에는 아래 실행 예시의 환경 변수를 그대로 넣습니다. 두 인스턴스가 host 네트워크를 공유하므로 `SERVER_PORT`만 다르면 됩니다.

알아둘 점이 있습니다.

- Pub/Sub은 메시지를 보관하지 않습니다. 구독이 붙기 전에 발행하면 그 메시지는 사라집니다. 확인 절차가 `PUBSUB NUMSUB`를 먼저 기다리는 이유입니다.
- 지표 경로는 인증이 필요합니다. CI는 공개 회원가입과 로그인 API로 토큰을 얻어 `/actuator/prometheus`를 읽습니다.
- 이 검증은 채널에서 목적지 handler까지를 봅니다. handler가 실제 SSE·WebSocket 연결로 밀어 넣는 마지막 구간은 도메인별 통합 테스트가 담당합니다.
- 발행한 인스턴스가 자기 메시지를 되받아 중복 전달하지 않는 성질은 `RealtimeRelayIntegrationTest`가 고정합니다. CI에서는 채널에 직접 넣는 방식이라 발행 인스턴스가 없습니다.

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
  -e OAUTH2_SUCCESS_REDIRECT_URI=<frontend-origin>/oauth/callback \
  -e OAUTH2_FAILURE_REDIRECT_URI=<frontend-origin>/sign-in \
  -e OAUTH2_LOCAL_CREDENTIAL_VERIFICATION_SECRET=<32-character-or-longer-secret> \
  -e GOOGLE_OAUTH_CLIENT_ID=<google-oauth-client-id> \
  -e GOOGLE_OAUTH_CLIENT_SECRET=<google-oauth-client-secret> \
  -e GOOGLE_OAUTH_REDIRECT_URI=https://<backend-domain>/login/oauth2/code/google \
  -e KAKAO_OAUTH_CLIENT_ID=<kakao-rest-api-key> \
  -e KAKAO_OAUTH_CLIENT_SECRET=<kakao-client-secret> \
  -e KAKAO_OAUTH_REDIRECT_URI=https://<backend-domain>/login/oauth2/code/kakao \
  -e NAVER_OAUTH_CLIENT_ID=<naver-client-id> \
  -e NAVER_OAUTH_CLIENT_SECRET=<naver-client-secret> \
  -e NAVER_OAUTH_REDIRECT_URI=https://<backend-domain>/login/oauth2/code/naver \
  -e KAFKA_BOOTSTRAP_SERVERS=mopl-kafka:9092 \
  mopl:local
```

기동 후 다음 요청이 성공해야 합니다.

```bash
curl --fail http://localhost:8080/actuator/health
```

정상 응답은 `{"status":"UP"}`입니다. Docker 컨테이너 상태가 `healthy`로 전환되는 기준은 `/actuator/health/liveness`이므로, 컨테이너가 `healthy`인데 위 응답이 `DOWN`일 수 있습니다. 그때는 어떤 component가 내려가 있는지 확인합니다.

## 운영 이미지 게시

배포 서버는 소스를 받아 다시 빌드하지 않습니다. CI가 검증한 commit과 대응하는 이미지를 그대로 내려받습니다. 서버에서 빌드하면 CI가 통과시킨 것과 실제로 도는 것이 같다는 보장이 없습니다.

`main` push와 `main` 브랜치의 수동 재실행에서만 게시합니다. PR이나 다른 브랜치의 수동 실행에서는 운영 태그를 만들지 않습니다. 검증되지 않은 커밋의 태그가 레지스트리에 남거나 다른 브랜치가 `main` 태그를 덮어쓰면 배포 대상을 고를 때 무엇이 검증된 것인지 구분할 수 없습니다.

게시는 `build`와 `Container smoke`가 모두 통과한 뒤에 실행됩니다.

### 태그 두 가지

| 태그 | 성질 | 용도 |
| --- | --- | --- |
| `<commit SHA>` | 한 번 붙으면 다른 이미지를 가리키지 않습니다 | 배포와 rollback의 기준 |
| `main` | 매 배포마다 다른 이미지를 가리킵니다 | 사람이 최신을 확인하는 용도 |

**배포와 rollback은 digest 또는 commit SHA 태그를 씁니다.** `main`만 쓰면 되돌릴 대상을 지목할 수 없습니다. 게시 결과의 태그와 digest는 워크플로 실행 요약에 남으므로 실행 로그를 뒤지지 않고 찾을 수 있습니다.

### 자격 증명

레지스트리 비밀번호를 저장소에 두지 않습니다. GitHub Actions가 OIDC로 IAM 역할을 맡아 ECR에 push하고, 배포 서버는 EC2 인스턴스 역할로 pull합니다. 양쪽 모두 장기 자격 증명이 없습니다.

필요한 저장소 변수는 다음과 같습니다. Secret이 아니라 변수로 둡니다. 셋 다 식별자이고 노출되어도 그 자체로 권한이 생기지 않습니다.

| 변수 | 설명 |
| --- | --- |
| `ECR_REPOSITORY` | ECR repository 이름 |
| `AWS_REGION` | repository가 있는 리전 |
| `AWS_DEPLOY_ROLE_ARN` | Actions가 OIDC로 맡을 IAM 역할 ARN |

**`ECR_REPOSITORY`가 비어 있으면 게시 job을 건너뜁니다.** AWS 리소스와 OIDC 역할은 #348이 만들므로, 그 전까지 `main` push마다 실패로 남기지 않기 위한 조건입니다. #348이 끝나고 변수를 채우면 그때부터 게시가 시작됩니다.

### 게시 후 확인

게시한 것이 곧 배포할 것입니다. 그래서 로컬 빌드 결과가 아니라 레지스트리에서 digest로 다시 받아 확인합니다.

- 기본 사용자가 비특권 사용자 `mopl`이다.
- healthcheck 메타데이터가 있고 `/actuator/health/liveness`를 본다.

어느 쪽이든 어긋나면 게시 job이 실패합니다.

### 레이어 캐시

빌드는 GitHub Actions 캐시를 씁니다. 캐시는 각 단계의 입력이 바뀌면 무효화되고, 소스를 복사하는 단계가 커밋마다 달라지므로 그 뒤 단계는 캐시를 쓰지 않습니다. 오래된 소스가 이미지에 남지 않습니다.

## 배포 서버 준비

빈 서버를 운영 Compose가 돌 수 있는 상태로 만드는 절차입니다. 콘솔에서 한 번 만지고 끝내지 않습니다. 같은 서버를 다시 만들 일이 생겼을 때 기억에 의존하면 빠뜨린 한 줄이 그날의 장애가 됩니다.

| 산출물 | 하는 일 |
| --- | --- |
| `deploy/aws/network.sh` | 보안 그룹과 고정 공인 IP |
| `deploy/bootstrap.sh` | Docker, 배포 사용자, 디렉터리, 호스트 방화벽 |
| `.env.example` | 환경 파일 서식 |

둘 다 여러 번 실행해도 결과가 같습니다. 중간에 실패해도 고친 뒤 그대로 다시 돌리면 됩니다.

### 서버 사양

| 항목 | 값 | 근거 |
| --- | --- | --- |
| 인스턴스 | `t3.large` (2 vCPU, 8 GiB) | 컨테이너 메모리 한도 합계가 약 7.3GB입니다 |
| OS | Ubuntu 24.04 LTS | `bootstrap.sh`가 이 배포판을 전제합니다 |
| 디스크 | gp3 30GB | 아래 계산 |
| 리전 | `ap-northeast-2` | 이미지 버킷과 ECR이 여기 있습니다 |

프리티어 대상인 `t3.micro`는 메모리가 1GB라 쓸 수 없습니다. 백엔드 한 인스턴스도 올라가지 않습니다.

디스크 30GB의 내역입니다. 이미지 약 4GB, 컨테이너 로그 상한 2GB(8개 서비스 × 250MB), 나머지가 PostgreSQL·Kafka·Elasticsearch 데이터입니다. 로그에 상한을 두지 않으면 이 계산이 의미가 없어집니다. `bootstrap.sh`가 Docker 로그 드라이버에 한도를 겁니다.

### 비용

`ap-northeast-2` 온디맨드 기준입니다. 2026년 8월에 AWS Pricing API에서 받은 값입니다.

| 항목 | 단가 | 월(730시간) |
| --- | --- | --- |
| `t3.medium` (2 vCPU, 4 GiB) | `$0.0520`/시간 | `$38.0` |
| `t3.large` (2 vCPU, 8 GiB) | `$0.1040`/시간 | `$75.9` |
| `t3.xlarge` (4 vCPU, 16 GiB) | `$0.2080`/시간 | `$151.8` |
| EBS gp3 30GB | `$0.0912`/GB·월 | `$2.7` |
| 공인 IPv4 1개 | `$0.0050`/시간 | `$3.7` |

`t3.large`를 계속 켜 두면 월 약 `$82`입니다.

| 운영 방식 | 월 비용 |
| --- | --- |
| 24시간 | 약 `$82` |
| 하루 8시간 | 약 `$31` |
| 중지 상태 | 약 `$6` |

인스턴스를 중지해도 EBS와 공인 IPv4 요금은 계속 붙습니다. 공인 IPv4는 인스턴스에 붙어 있는지와 무관하게 과금되므로, 서버를 없앨 때 Elastic IP도 함께 release 해야 합니다. 그러지 않으면 쓰지 않는 주소에 매달 `$3.7`이 나갑니다.

크레딧으로 운영한다면 상시 가동은 두 달을 넘기기 어렵습니다. 시연 기간에만 켜는 쪽이 현실적입니다.

### 네트워크 경계

외부에 여는 것은 80과 443뿐입니다.

```bash
SSH_ALLOWED_CIDR=$(curl -s https://checkip.amazonaws.com)/32 bash deploy/aws/network.sh
```

| 포트 | 허용 범위 | 이유 |
| --- | --- | --- |
| 80 | 전체 | HTTPS 전환과 ACME 인증서 발급 |
| 443 | 전체 | HTTPS |
| 22 | 지정한 주소만 | 관리 접속 |

PostgreSQL, Redis, Kafka, Elasticsearch는 규칙을 두지 않습니다. Compose가 호스트 포트를 열지 않으므로 규칙이 없으면 닿을 수 없습니다. Redis와 Kafka에는 인증이 없어서, 한 번 열리면 그 순간 그대로 공개됩니다.

경계는 두 겹입니다. 보안 그룹이 1차, 호스트의 `ufw`가 2차입니다. 보안 그룹을 누가 넓게 고쳐도 호스트에서 한 번 더 막힙니다.

### 호스트 부트스트랩

```bash
sudo SSH_ALLOWED_CIDR=203.0.113.10/32 bash deploy/bootstrap.sh
```

`SSH_ALLOWED_CIDR` 없이 실행하면 스크립트가 먼저 멈춥니다. 기본 정책이 deny인 방화벽을 SSH 허용 없이 켜면 지금 붙어 있는 접속까지 끊기고, 다시 들어갈 방법이 콘솔밖에 남지 않습니다.

하는 일은 이렇습니다.

- 배포 전용 사용자 `deploy` 생성. 비밀번호 로그인은 막고 SSH 키로만 접속합니다
- Docker Engine과 Compose 플러그인 설치. 배포판의 `docker.io`가 아니라 Docker 공식 저장소를 씁니다. Compose v2 플러그인이 배포판 패키지에 없습니다
- 컨테이너 로그를 서비스당 250MB로 제한
- 설정과 데이터 디렉터리 생성
- `ufw`로 80·443·지정한 SSH 범위만 허용

`docker` 그룹은 사실상 root 권한입니다. 배포 사용자에게만 줍니다.

### 디렉터리와 권한

| 경로 | 소유자 | 권한 | 내용 |
| --- | --- | --- | --- |
| `/etc/mopl` | `root:deploy` | `0750` | 설정 |
| `/etc/mopl/prod.env` | `deploy:deploy` | `0600` | 환경 변수와 Secret |
| `/srv/mopl/app` | `deploy:deploy` | `0755` | Compose 파일과 Caddyfile |
| `/srv/mopl/data/*` | 각 이미지의 실행 사용자 | `0700` | 영속 데이터 |

운영 환경 파일은 저장소에 두지 않습니다. `.gitignore`가 `.env`와 `.env.*`를 막고 `.env.example`만 남깁니다. 서버에서는 `/etc/mopl/prod.env`에 두고 `deploy`만 읽습니다.

데이터 디렉터리의 소유자는 이미지에서 직접 읽어 맞춥니다. bind mount는 named volume과 달리 Docker가 소유자를 고쳐 주지 않습니다. PostgreSQL과 Redis는 entrypoint가 root로 시작해 스스로 맞추지만, Elasticsearch와 Kafka는 이미지가 실행 사용자를 지정해 두어 처음부터 비특권 사용자로 뜹니다. 소유자가 맞지 않으면 기동하지 못합니다.

UID를 문서에 적어 두지 않는 이유는, 이미지가 올라가면서 값이 바뀌면 적어 둔 쪽이 조용히 틀리기 때문입니다. 그 사실은 다음 서버를 만들 때에야 드러납니다.

Elasticsearch 이미지는 nori 플러그인을 넣어 직접 만든 것이라 ECR 로그인 전에는 받을 수 없습니다. 그때는 기본값을 쓰고, `prod.env`를 채운 뒤 `bootstrap.sh`를 다시 실행하면 실제 이미지에서 읽습니다.

### 도메인과 HTTPS

A 레코드를 Elastic IP로 지정합니다. 인스턴스를 멈췄다 켜면 공인 IP가 바뀌므로 고정 주소가 필요합니다. 바뀐 주소를 모르는 동안에는 인증서 갱신도 실패합니다.

인증서는 Caddy가 기동할 때 Let's Encrypt에서 받고 만료 전에 갱신합니다. 발급에는 80번 포트로 오는 ACME 요청이 필요하므로 80을 닫으면 안 됩니다. HTTP 요청을 HTTPS로 보내는 것도 Caddy가 합니다.

`MOPL_DOMAIN` 하나가 Caddy의 인증서 대상, CORS origin, WebSocket origin, OAuth Callback URI를 모두 만듭니다. 각 Provider Console에 등록한 Callback URI가 이 도메인과 같아야 합니다.

### 최초 기동

서버에는 저장소 전체가 필요하지 않습니다. `docker-compose.prod.yml`과 `deploy/` 두 가지면 됩니다. 아래 2번부터는 그 파일들이 있는 곳에서 실행합니다.

```bash
# 0. 스크립트와 Compose 파일을 서버로 가져옵니다
git clone --depth 1 https://github.com/ow00us/sb11-mopl-team1.git ~/mopl && cd ~/mopl

# 1. 네트워크 경계 (로컬에서 AWS CLI 로)
SSH_ALLOWED_CIDR=$(curl -s https://checkip.amazonaws.com)/32 bash deploy/aws/network.sh

# 2. 인스턴스 생성 후 접속해 부트스트랩
sudo SSH_ALLOWED_CIDR=203.0.113.10/32 bash deploy/bootstrap.sh

# 3. 환경 파일 작성. .env.example 의 항목을 모두 채웁니다
sudo -u deploy vi /etc/mopl/prod.env

# 4. Compose 파일 배치
sudo -u deploy cp docker-compose.prod.yml /srv/mopl/app/
sudo -u deploy mkdir -p /srv/mopl/app/deploy
sudo -u deploy cp deploy/Caddyfile /srv/mopl/app/deploy/

# 5. 데이터 디렉터리 소유자를 실제 이미지 기준으로 다시 맞춤
sudo bash deploy/bootstrap.sh

# 6. ECR 로그인 후 기동
aws ecr get-login-password --region ap-northeast-2 \
  | sudo -u deploy docker login --username AWS --password-stdin \
      "$(aws sts get-caller-identity --query Account --output text).dkr.ecr.ap-northeast-2.amazonaws.com"
sudo -u deploy docker compose -f /srv/mopl/app/docker-compose.prod.yml \
  --env-file /etc/mopl/prod.env up -d
```

기동 후 확인합니다.

```bash
sudo -u deploy docker compose -f /srv/mopl/app/docker-compose.prod.yml ps
curl -fsS https://<도메인>/actuator/health/readiness
```

모든 서비스가 `healthy`여야 합니다. 어느 하나가 계속 재시작한다면 먼저 그 서비스의 로그를 봅니다. Elasticsearch나 Kafka가 권한 오류로 멈춰 있다면 5번을 건너뛴 경우입니다.

기동에 필요한 값이 빠졌다면 백엔드가 뜨지 않고 어떤 값이 문제인지 로그에 남습니다. 형식이 잘못된 값은 `ProdEnvironmentValidator`가 기동 시점에 걸러 내며, 문제가 여럿이면 한 번에 모두 보고합니다.

## 운영 Docker Compose

운영 런타임은 `docker-compose.prod.yml`에 있습니다. 로컬 개발용 `docker-compose.yml`과 분리한 이유는 그쪽이 PostgreSQL, Redis, Kafka 포트를 호스트에 열기 때문입니다. 그대로 운영에 쓰면 인증이 없는 Redis와 Kafka가 공인 IP에 그대로 열립니다.

```bash
docker compose -f docker-compose.prod.yml --env-file /etc/mopl/prod.env up -d
```

이미지는 여기서 빌드하지 않고 레지스트리에서 받습니다. 서버에서 빌드하면 CI가 통과시킨 것과 실제로 도는 것이 같다는 보장이 없습니다.

### 서비스 구성

| 서비스 | 이미지 | 호스트 포트 |
| --- | --- | --- |
| `caddy` | `caddy:2-alpine` | `80`, `443` |
| `gateway` | `FRONTEND_IMAGE` | 없음 |
| `backend-a`, `backend-b` | `BACKEND_IMAGE` | 없음 |
| `postgres` | `postgres:16` | 없음 |
| `redis` | `redis:7` | 없음 |
| `kafka` | `apache/kafka:3.8.0` | 없음 |
| `elasticsearch` | `ELASTICSEARCH_IMAGE` | 없음 |

**호스트에 포트를 여는 것은 `caddy` 하나뿐입니다.** 나머지는 Compose 네트워크 안에서만 닿습니다. 관리 작업은 SSH로 접속한 뒤 `docker compose exec`로 합니다.

네트워크는 역할별로 나눕니다. `edge`는 Caddy와 Gateway, `app`은 Gateway와 백엔드, `data`는 백엔드와 데이터 서비스입니다. Gateway가 데이터 서비스에 닿을 이유가 없고 Caddy가 백엔드에 직접 닿을 이유도 없습니다.

### 백엔드 두 인스턴스

A와 B는 같은 이미지와 **완전히 같은 환경 변수**를 받습니다. 다른 것은 이름뿐입니다. YAML 앵커로 한 곳에 정의하고 양쪽이 참조하므로 설정이 갈릴 수 없습니다. 갈리면 두 인스턴스가 서로 다르게 동작하고, 그 차이는 요청이 어느 쪽으로 갔는지에 따라 간헐적으로만 드러납니다.

Gateway가 두 인스턴스에 분산하며 세션 고정을 쓰지 않습니다. 근거는 위 OAuth2 절에 있습니다.

컨테이너 healthcheck는 `/actuator/health/liveness`를 봅니다. 전체 health를 재시작 조건으로 두면 프로세스를 다시 띄운다고 풀리지 않는 상태까지 재시작을 부릅니다.

### 메모리 한도

백엔드 이미지는 `-XX:MaxRAMPercentage=75.0`으로 힙을 잡습니다. 이 값은 **컨테이너 메모리 한도**를 기준으로 계산되므로, 한도가 없으면 JVM 이 호스트 전체 메모리를 기준으로 잡습니다. 인스턴스가 둘이면 합쳐서 호스트보다 큰 힙을 요구하고, 같은 서버의 PostgreSQL·Kafka·Elasticsearch 까지 함께 밀려납니다.

그래서 모든 상태 있는 서비스에 한도를 둡니다. 기본값 합계는 약 7GB 입니다.

| 서비스 | 변수 | 기본값 |
| --- | --- | --- |
| 백엔드 A·B | `BACKEND_MEM_LIMIT` | `1536m` 각각 |
| PostgreSQL | `POSTGRES_MEM_LIMIT` | `1g` |
| Redis | `REDIS_MEM_LIMIT` | `512m` |
| Kafka | `KAFKA_MEM_LIMIT` | `1g` |
| Elasticsearch | `ELASTICSEARCH_MEM_LIMIT` | `1536m` |

**최소 8GB 메모리를 권장합니다.** 7GB 를 컨테이너가 쓰고 나머지가 OS 몫입니다. 여유를 두려면 16GB 를 씁니다.

### 영속 데이터

`MOPL_DATA_ROOT` 아래에 서비스별로 둡니다. 기본값은 `/srv/mopl/data`입니다.

| 데이터 | 경로 |
| --- | --- |
| PostgreSQL | `${MOPL_DATA_ROOT}/postgres` |
| Redis | `${MOPL_DATA_ROOT}/redis` |
| Kafka | `${MOPL_DATA_ROOT}/kafka` |
| Elasticsearch | `${MOPL_DATA_ROOT}/elasticsearch` |

Redis는 AOF를 켭니다. refresh token 세션과 OAuth2 인가 요청이 여기 있어, 재시작으로 사라지면 로그인한 사용자가 모두 끊깁니다.

Kafka에 볼륨을 두는 이유는 컨테이너를 다시 만들 때 토픽과 소비 offset이 사라지기 때문입니다. `prod`는 `auto-offset-reset`이 `latest`라 offset이 없어지면 그동안의 이벤트를 건너뜁니다.

### 환경 파일

`.env.example`을 복사해 실제 값을 채웁니다. 저장소에는 예시만 두고 실제 파일은 `.gitignore`가 막습니다.

```bash
sudo install -o deploy -g deploy -m 600 /dev/null /etc/mopl/prod.env
```

Compose가 `MOPL_DOMAIN` 하나에서 CORS origin, WebSocket origin, OAuth Callback URI를 모두 만듭니다. 도메인을 바꿀 때 한 곳만 고치면 되고, 값들이 서로 어긋날 수 없습니다. 각 Provider Console에 등록한 Callback URI가 이 도메인과 같아야 합니다.

## 배포와 rollback

배포는 자동으로 돌지 않습니다. `Deploy` 워크플로를 사람이 실행합니다. `main` push마다 운영에 나가면 되돌릴 판단을 할 사람이 없는 시간에도 배포가 일어납니다.

| 산출물 | 하는 일 |
| --- | --- |
| `.github/workflows/deploy.yml` | 승인 경계, 마이그레이션 확인, 서버 호출 |
| `deploy/deploy.sh` | 서버에서 이미지를 교체하고 실패하면 되돌립니다 |
| `deploy/check-destructive-migration.sh` | 되돌릴 수 없는 스키마 변경을 배포 전에 막습니다 |
| `deploy/test-deploy.sh` | 성공·실패·rollback 경로 검증 |

### 실행

Actions 탭의 `Deploy` 워크플로를 실행합니다.

| 입력 | 설명 |
| --- | --- |
| `backend_image` | digest 또는 commit SHA 태그 |
| `frontend_image` | 비우면 지금 것을 그대로 둡니다 |
| `allow_destructive_migration` | 아래 "되돌릴 수 없는 변경" 참고 |

**이동 태그는 거부됩니다.** `main`이나 `latest`는 나중에 다른 이미지를 가리키므로, 무엇이 배포됐는지 지목할 수 없고 되돌릴 대상도 정해지지 않습니다.

`production` environment를 씁니다. 승인자와 대상 브랜치 제한은 저장소 설정에 둡니다. 같은 환경에 배포가 겹치지 않도록 concurrency를 걸었고, 진행 중인 배포를 취소하지 않습니다. 배포 도중 취소는 절반만 교체된 상태를 남깁니다.

### 교체 순서

```
A 교체 → A health 확인 → B 교체 → B health 확인 → gateway
```

백엔드를 한 번에 둘 다 바꾸지 않습니다. Flyway는 애플리케이션 기동 시점에 돌므로 마이그레이션은 **A가 적용**하고 B는 이미 적용된 스키마 위에서 뜹니다.

A가 health를 통과하지 못하면 **B는 건드리지 않습니다.** B가 이전 이미지로 계속 요청을 받습니다.

교체 전에 `docker compose config`와 이미지 pull을 먼저 확인합니다. 설정이 깨진 채로 컨테이너를 교체하면 멀쩡히 돌던 것까지 내려갑니다.

### health 판정

| 대상 | 쓰는 곳 | 이유 |
| --- | --- | --- |
| `/actuator/health/liveness` | 컨테이너 재시작 조건 | 프로세스가 살아 있는지만 봅니다 |
| `/actuator/health/readiness` | 배포 성공 판정 | 요청을 받을 준비가 됐는지가 교체 기준입니다 |
| `/actuator/health` | 참고 기록 | DB·Kafka·SMTP까지 포함합니다 |

전체 health를 재시작 조건으로 두면, 프로세스를 다시 띄운다고 풀리지 않는 상태에서 재시작만 반복합니다. 배포 판정에도 쓰지 않습니다 — 외부 의존 하나가 내려간 것과 새 이미지가 잘못된 것은 다른 문제입니다. 전체 health가 `UP`이 아니면 기록만 남기고 진행합니다.

기본 대기 시간은 180초입니다. `HEALTH_TIMEOUT`으로 조정합니다.

### rollback

health가 시간 안에 통과하지 않으면 환경 파일을 이전 이미지로 되돌리고 두 인스턴스를 다시 띄운 뒤, 복구된 상태의 health까지 확인합니다.

되돌릴 지점은 새 값을 쓰기 **전에** 기록합니다. 실패한 뒤에 무엇으로 돌아가야 하는지 찾기 시작하면 늦습니다.

**이 rollback은 이미지와 런타임 설정까지입니다. 이미 적용된 마이그레이션은 되돌리지 않습니다.**

### 되돌릴 수 없는 변경

이미지를 되돌려도 스키마는 되돌아가지 않습니다. 컬럼을 지우거나 이름을 바꾸는 마이그레이션이 섞이면, 이전 코드가 없어진 컬럼을 찾게 되어 되돌린 뒤에도 고장난 상태 그대로입니다.

배포 전에 확인해 막습니다.

```bash
bash deploy/check-destructive-migration.sh <이전_commit> <새_commit>
```

`DROP TABLE`, `DROP COLUMN`, `DROP CONSTRAINT`, `RENAME`, 컬럼 타입 변경, `SET NOT NULL`, `TRUNCATE`를 찾습니다. `ADD COLUMN`, `CREATE TABLE`, `CREATE INDEX`는 막지 않습니다 — 이전 코드는 새로 생긴 것을 모르므로 그대로 동작합니다.

이전 배포 commit을 찾을 수 없으면 통과가 아니라 **실패**로 끝냅니다. 모르는 것을 괜찮다고 답하면 확인 절차가 있으나 마나입니다. 워크플로가 `fetch-depth: 0`으로 checkout하는 이유입니다.

걸렸다면 보통 두 번으로 나눕니다.

1. 새 코드가 옛 컬럼 없이도 동작하도록 배포합니다
2. 그 배포가 안정된 뒤에 컬럼을 지우는 마이그레이션을 배포합니다

나눌 수 없다고 판단했다면 `allow_destructive_migration`을 켭니다. 그 경우 실패 시 복구는 수동입니다.

### 배포 기록

서버의 `/etc/mopl/deploy-state.env`에 남습니다. 워크플로 실행 요약에도 같은 내용이 붙습니다.

| 항목 | 내용 |
| --- | --- |
| `DEPLOY_RESULT` | `succeeded` 또는 `failed` |
| `DEPLOY_COMMIT` | 배포한 commit |
| `DEPLOYED_BACKEND_IMAGE` | 배포한 이미지 |
| `PREVIOUS_BACKEND_IMAGE` | 되돌릴 대상 |
| `STARTED_AT`, `FINISHED_AT` | UTC |
| `DETAIL` | 실패 사유 |

다음 배포는 이 파일의 `DEPLOY_COMMIT`을 읽어 마이그레이션을 비교합니다.

이 파일은 `bootstrap.sh`가 미리 만듭니다. `/etc/mopl`은 group에 쓰기 권한이 없어 `deploy`가 새 파일을 만들 수 없고, 파일이 있으면 내용만 바꿀 수 있습니다. 배포 스크립트에 `sudo`를 주지 않기 위한 것입니다.

### GitHub Actions 자격 증명

장기 자격 증명을 저장소에 두지 않습니다. OIDC로 IAM 역할을 맡습니다.

역할의 신뢰 정책은 스크립트로 만듭니다.

```bash
APPLY=true bash deploy/aws/github-oidc-role.sh
```

계정 ID와 저장소 ID를 파일에 적어 두지 않고 실행 시점에 조회합니다. 적어 두면 다른 계정에서 그대로 쓸 수 없고, 틀린 값을 넣어도 적용할 때까지 드러나지 않습니다.

GitHub은 OIDC subject를 두 형태로 낼 수 있습니다.

```
repo:OWNER/REPO:ref:refs/heads/main
repo:OWNER@OWNER_ID/REPO@REPO_ID:ref:refs/heads/main
```

어느 쪽이 오는지는 저장소 설정에 달려 있습니다. 한쪽만 넣어 두면 다른 쪽이 왔을 때 `sts:AssumeRoleWithWebIdentity`가 거부되고, 그 사실은 배포가 처음 돌 때에야 드러납니다. 스크립트는 둘 다 허용합니다.

지금 저장소가 어느 형식을 쓰는지는 이렇게 확인합니다.

```bash
gh api repos/ow00us/sb11-mopl-team1/actions/oidc/customization/sub --jq .sub_claim_prefix
```

### 서버 접속 Secret

| Secret | 내용 |
| --- | --- |
| `DEPLOY_HOST` | 배포 서버 주소 |
| `DEPLOY_USER` | 배포 전용 사용자 |
| `DEPLOY_SSH_KEY` | 그 사용자의 SSH 개인 키 |

키는 실행이 끝나면 지웁니다. `BatchMode=yes`로 붙어 자격 증명을 물어보지 않고 실패합니다. 프롬프트가 뜨면 timeout까지 붙잡고 있습니다.

배포 스크립트는 서버에 있는 사본이 아니라 **이번 commit의 것**을 보내 실행합니다. 서버 사본은 언제 갱신됐는지 알 수 없어, 배포되는 코드와 배포하는 절차가 어긋납니다.

### 검증

실제 서버 없이 성공·실패·rollback 경로를 확인합니다.

```bash
bash deploy/test-deploy.sh
```

`docker`를 가짜로 바꿔 놓고 돌립니다. 실제 서버에서만 확인할 수 있는 절차라면 고칠 때마다 서버가 필요하고, 그러면 rollback 경로는 사실상 한 번도 확인되지 않습니다. 정작 필요한 순간에 처음 돌아가는 코드가 됩니다.

확인하는 것은 이렇습니다.

- A를 먼저, B를 나중에 교체하는지
- 새 이미지와 되돌릴 지점이 기록되는지
- A가 실패하면 되돌리고 **B는 건드리지 않는지**
- 되돌린 뒤 환경 파일의 Secret이 그대로인지
- 설정이 깨지면 컨테이너를 아예 건드리지 않는지
- 이동 태그를 거부하는지

## CI 컨테이너 smoke 검증

`develop` 또는 `main` 대상 PR과 두 브랜치의 push에서는 Gradle 빌드·테스트가
통과한 뒤 운영 이미지 smoke 검증을 실행합니다.

CI는 PostgreSQL 16과 Redis 7을 준비하고 다음 항목을 자동으로 확인합니다.

- 저장소의 Dockerfile로 운영 이미지를 빌드할 수 있다.
- 실행 이미지의 기본 사용자가 비특권 사용자 `mopl`이다.
- 빈 PostgreSQL에 Flyway 마이그레이션을 적용하고 Redis에 연결한다.
- prod 프로파일 애플리케이션의 Docker health status가 `healthy`가 된다.
- `/actuator/health`가 `UP` 상태를 반환한다.
- 같은 Redis를 쓰는 두 인스턴스 사이에서 실시간 중계가 동작한다. 확인 항목은 위 두 인스턴스 중계 smoke에 정리했습니다.

이 단계에서 사용하는 DB 자격값과 JWT Secret은 격리된 CI 실행에서만 쓰는 테스트
값입니다. 이미지를 레지스트리에 게시하거나 실제 운영 환경에 배포하지 않습니다.

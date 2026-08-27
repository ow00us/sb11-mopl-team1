# 스테이징 기준 데이터셋 주입

비공개 `ow00us/mopl-staging-dataset` 저장소의 검증 완료 데이터셋을 스테이징에 단독 주입합니다. 데이터 저장소에는 CSV와 계약만 두고, 실행 도구는 이 저장소에서 관리합니다.

## 안전 경계

- `MOPL_DOMAIN=mopl-team1-staging.duckdns.org`와 `DEPLOY_ENVIRONMENT=staging`을 모두 확인합니다.
- IMDS에서 읽은 실제 EC2 인스턴스 ID가 명령에 지정한 값과 다르면 중단합니다.
- 데이터셋과 배포 백엔드 Git SHA, Flyway V20이 정확히 일치해야 합니다.
- `--confirm RESET-STAGING` 없이는 기존 데이터를 초기화하지 않습니다.
- 주입 전에 `pg_dump` 백업을 `/srv/mopl/backups`에 남깁니다.
- PostgreSQL 초기화와 CSV 주입은 하나의 트랜잭션으로 처리합니다.
- `v1`과 `v2`는 누적하지 않습니다. 각 측정 전에 하나만 단독 주입합니다.

## 실행

데이터셋과 이 디렉터리의 세 파일을 서버의 임시 작업 디렉터리에 준비한 뒤 실행합니다.

먼저 실제 데이터를 바꾸지 않는 사전 검증을 실행합니다.

```bash
sudo bash deploy/staging-dataset/import.sh \
  --dataset-dir /srv/mopl/import/datasets/v1 \
  --dataset-version v1 \
  --expected-instance-id i-07c2c74931f2bb216 \
  --confirm RESET-STAGING \
  --preflight-only
```

인스턴스·도메인·배포 SHA·Flyway·체크섬이 모두 일치한 뒤에만 실제 주입을 실행합니다.

```bash
sudo bash deploy/staging-dataset/import.sh \
  --dataset-dir /srv/mopl/import/datasets/v1 \
  --dataset-version v1 \
  --expected-instance-id i-07c2c74931f2bb216 \
  --confirm RESET-STAGING
```

실행 중에는 외부 요청을 막기 위해 Caddy·gateway·백엔드를 잠시 내립니다. PostgreSQL 주입 후 Redis를 비우고 `contents` 인덱스를 삭제한 다음 백엔드를 재기동하여 전체 콘텐츠를 다시 색인합니다.

## 결과와 복구

- 실행 결과: `/srv/mopl/dataset-runs/<UTC>-<version>.txt`
- 주입 전 DB 백업: `/srv/mopl/backups/before-<version>-<UTC>.dump`

주입 실패 시 PostgreSQL 트랜잭션은 롤백되고 종료 trap이 서비스를 다시 기동합니다. DB 커밋 후 Elasticsearch 백필 단계에서 실패했다면 같은 버전을 다시 실행하거나 `contents` 인덱스를 삭제하고 백엔드를 재기동합니다.

백업 복구가 필요하면 먼저 요청을 차단하고 대상 파일을 확인한 뒤 다음 순서로 실행합니다.

```bash
docker compose -f /srv/mopl/app/docker-compose.prod.yml \
  --env-file /etc/mopl/staging.env stop caddy gateway backend-a backend-b

docker compose -f /srv/mopl/app/docker-compose.prod.yml \
  --env-file /etc/mopl/staging.env exec -T postgres \
  pg_restore --clean --if-exists --no-owner --no-acl \
  -U mopl -d mopl < /srv/mopl/backups/<확인한-백업>.dump
```

복구 명령은 스테이징 전용이며 파일명을 추정하여 실행하지 않습니다.

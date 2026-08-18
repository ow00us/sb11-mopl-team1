#!/usr/bin/env bash
#
# Flyway 마이그레이션 버전 중복을 검사한다.
#
# 같은 버전을 가진 마이그레이션이 둘 있으면 Flyway 는 SQL 실행 전 해석 단계에서 실패하고,
# Flyway 를 쓰는 모든 Spring 컨텍스트가 로드되지 않아 데이터베이스를 쓰는 테스트가 전부
# initializationError 로 실패한다. 실패 목록만 보면 개별 테스트의 결함처럼 보인다.
#
# CI 의 pull_request 검사는 PR head 와 base 를 합친 트리에서 실행되므로, 브랜치와 develop
# 사이의 번호 충돌도 이 검사에서 드러난다.
set -euo pipefail

MIGRATION_DIR="src/main/resources/db/migration"

if [ ! -d "$MIGRATION_DIR" ]; then
  echo "마이그레이션 디렉터리를 찾을 수 없습니다: $MIGRATION_DIR" >&2
  exit 1
fi

# .sql 만 대상으로 한다. V4, V7 처럼 .sql.conf 사이드카가 있는 버전을 함께 세면 항상
# 중복으로 잡힌다.
versions="$(
  ls "$MIGRATION_DIR"/*.sql \
    | sed -nE 's#.*/V([0-9][0-9._]*)__.*\.sql$#V\1#p' \
    | sort
)"

if [ -z "$versions" ]; then
  echo "검사할 마이그레이션이 없습니다: $MIGRATION_DIR" >&2
  exit 1
fi

duplicates="$(printf '%s\n' "$versions" | uniq -d)"

if [ -n "$duplicates" ]; then
  echo "마이그레이션 버전이 중복됐습니다." >&2
  printf '%s\n' "$duplicates" | while read -r version; do
    [ -z "$version" ] && continue
    echo "  $version" >&2
    ls "$MIGRATION_DIR/${version}__"*.sql | sed 's#.*/#    #' >&2
  done
  echo >&2
  echo "브랜치의 마이그레이션 번호를 develop 최신 번호 다음으로 옮기세요." >&2
  exit 1
fi

echo "마이그레이션 버전 중복 없음 ($(printf '%s\n' "$versions" | wc -l | tr -d ' ')개 확인)"

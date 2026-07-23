-- 스캐폴딩이 잘 도는지 확인하기 위한 예시 테이블입니다. 실제 도메인 개발을 시작하면 지우거나 바꾸시면 됩니다.
CREATE TABLE sample (
    id         UUID PRIMARY KEY,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    name       VARCHAR(255) NOT NULL
);

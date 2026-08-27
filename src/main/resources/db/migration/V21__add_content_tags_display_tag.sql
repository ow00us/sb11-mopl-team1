-- content_tags에 원본 표기(대소문자 등)를 보존하는 display_tag 컬럼을 추가한다.
-- 기존 tag 컬럼은 검색·중복 방지를 위한 정규화(소문자) 키로 계속 쓰인다.
--
-- 이미 저장된 행은 원본 표기를 복구할 수 없으므로 tag 값을 그대로 백필한다.
-- 이 마이그레이션 이후 새로 추가되거나 수정되는 태그부터 원본 표기가 정확히 보존된다.
ALTER TABLE content_tags
    ADD COLUMN display_tag VARCHAR(100);

UPDATE content_tags
SET display_tag = tag
WHERE display_tag IS NULL;

ALTER TABLE content_tags
    ALTER COLUMN display_tag SET NOT NULL;

-- 표시 전용 이름(원본 표기, 띄어쓰기 포함). korean_name 은 중복 방지 match key 로 유지한다.
-- DEFAULT '' 는 raw INSERT 하위 호환용이며, 애플리케이션 쓰기 경로가 실제 값을 보장한다.
ALTER TABLE food ADD COLUMN display_name VARCHAR(255) NOT NULL DEFAULT '' AFTER korean_name;

UPDATE food SET display_name = korean_name WHERE display_name = '';

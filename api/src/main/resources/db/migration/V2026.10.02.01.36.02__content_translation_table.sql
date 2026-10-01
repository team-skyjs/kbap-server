-- KB-678: 사용자 글(1차: 리뷰 본문) 번역 결과 캐시(짝 A — 스키마 선행). 가산만: 새 표 하나, 기존 표·값을 바꾸지 않는다.
-- 이 파일은 짝 B(코드) PR 에 byte 단위로 같게 동봉한다 — Flyway 체크섬은 주석까지 포함한다.
--
-- (대상 종류, 대상 id, 언어)별 번역 1건. source_hash 는 번역한 원문의 SHA-256(hex 64자)이다 —
-- 요청 시 원문 해시가 같으면 저장본을 돌려주고(LLM 호출 없음), 다르면 다시 번역해 같은 행을 덮는다.
-- 대상은 종류가 여럿으로 늘 수 있어(게시글·댓글 등) FK 를 두지 않는다. 대상이 지워져도 행은 남지만
-- 번역 요청은 대상의 가시성 검사를 먼저 거치므로 노출되지 않는다.
-- 같은 키의 동시 요청은 UNIQUE 위에서 INSERT ... ON DUPLICATE KEY UPDATE 로 한 행에 수렴한다.
-- 엔진·문자셋·콜레이션은 원문(food_review 등)과 같게 명시한다.
CREATE TABLE content_translation
(
    id              BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    target_type     VARCHAR(20)               NOT NULL,
    target_id       BIGINT                    NOT NULL,
    language        VARCHAR(10)               NOT NULL,
    source_hash     CHAR(64)                  NOT NULL,
    translated_text TEXT                      NOT NULL,
    status          ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME(6)               NOT NULL,
    updated_at      DATETIME(6)               NOT NULL,
    UNIQUE KEY uk_content_translation_target_language (target_type, target_id, language)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

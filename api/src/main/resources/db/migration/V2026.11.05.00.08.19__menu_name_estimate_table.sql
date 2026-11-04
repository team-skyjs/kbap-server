-- S-959(KB-991, 예진 11/4): 메뉴판 스캔에서 DB 에 없는(매칭·벡터 대체 실패) 메뉴 이름을 LLM 으로 추정한 결과의 영구 캐시.
-- 행 = 이름 키 1개(KoreanMenuNameNormalizer.matchKey — food.korean_name 과 같은 정규화). 같은 이름은 다시 호출하지 않는다.
-- kind: DISH·DRINK·NOT_FOOD(가게 이름·구역 제목·가격·안내 문구). NOT_FOOD 면 이름·설명·코드는 비어 있다.
-- avoidance_codes: 추정 주재료를 회피 코드(IngredientCode 이름) 배열로. 판정은 코드 소유 — LLM 항목은 SAFE 가 아니다(CAUTION 또는 UNKNOWN).
-- spiciness: 0~10, 판단 불가 -1(food.spiciness 와 같은 규칙). confidence: 0~1. model: 추정한 모델 이름(비용·품질 추적).
-- INT 로 둔다(TINYINT 는 Hibernate validate 가 Int 매핑과 어긋난다). FK 는 없다(원장성 캐시, food 와 연결하지 않는다).
-- 가산만: 새 테이블. 기존 테이블·값은 건드리지 않는다.
CREATE TABLE menu_name_estimate
(
    id              BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name_key        VARCHAR(255)              NOT NULL,
    kind            VARCHAR(16)               NOT NULL,
    english_name    VARCHAR(200)              NULL,
    description     VARCHAR(500)              NULL,
    avoidance_codes JSON                      NULL,
    spiciness       INT                       NOT NULL DEFAULT -1,
    confidence      DECIMAL(4, 3)             NULL,
    model           VARCHAR(64)               NOT NULL,
    status          ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME(6)               NOT NULL,
    updated_at      DATETIME(6)               NOT NULL,
    CONSTRAINT uq_menu_name_estimate_name_key UNIQUE (name_key)
);

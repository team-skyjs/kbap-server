-- food 컨텍스트: 음식 + 재료 + 다국어 번역(ko 원문 + 9개 대상 언어).
-- 공통 컬럼(status=EntityStatus 소프트삭제, created_at, updated_at)은 BaseEntity(@MappedSuperclass)에서 온다.
-- 재료(ingredient)는 공유 지식베이스 — 여러 음식이 같은 재료를 ingredient_id 로 참조한다(복제 금지).
CREATE TABLE food (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    korean_name VARCHAR(255) NOT NULL,                  -- ko 원문(조회 매칭 키)
    image_ref   VARCHAR(500) NULL,
    status      VARCHAR(20)  NOT NULL,                  -- EntityStatus: ACTIVE/DELETED
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_food_korean_name (korean_name)
);

CREATE TABLE ingredient (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    korean_name VARCHAR(255) NOT NULL,                  -- ko 원문(공유 재료 매칭 키)
    icon_ref    VARCHAR(500) NULL,
    status      VARCHAR(20)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ingredient_korean_name (korean_name)
);

-- 번역 테이블: ko 는 저장하지 않는다(원문은 본 테이블 korean_name). lang_code 는 9개 대상 언어만 허용.
CREATE TABLE food_name_translation (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    food_id    BIGINT       NOT NULL,
    lang_code  VARCHAR(10)  NOT NULL,                   -- zh-Hans·en·ja·zh-Hant·vi·id·th·ru·es
    name       VARCHAR(255) NOT NULL,
    status     VARCHAR(20)  NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_fnt_food FOREIGN KEY (food_id) REFERENCES food(id),
    UNIQUE KEY uq_fnt (food_id, lang_code),
    CONSTRAINT ck_fnt_lang CHECK (lang_code IN ('zh-Hans','en','ja','zh-Hant','vi','id','th','ru','es'))
);

CREATE TABLE ingredient_name_translation (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    ingredient_id BIGINT       NOT NULL,
    lang_code     VARCHAR(10)  NOT NULL,
    name          VARCHAR(255) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_int_ingredient FOREIGN KEY (ingredient_id) REFERENCES ingredient(id),
    UNIQUE KEY uq_int (ingredient_id, lang_code),
    CONSTRAINT ck_int_lang CHECK (lang_code IN ('zh-Hans','en','ja','zh-Hant','vi','id','th','ru','es'))
);

CREATE TABLE food_ingredient (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    food_id           BIGINT      NOT NULL,
    ingredient_id     BIGINT      NOT NULL,
    inclusion_percent INT         NOT NULL,             -- 0~100 (여러 레시피 기준 포함 확률); 응답 정렬은 서비스단에서 이 값 내림차순
    status            VARCHAR(20) NOT NULL,
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_fi_food FOREIGN KEY (food_id) REFERENCES food(id),
    CONSTRAINT fk_fi_ingredient FOREIGN KEY (ingredient_id) REFERENCES ingredient(id),
    UNIQUE KEY uq_food_ingredient (food_id, ingredient_id)
);

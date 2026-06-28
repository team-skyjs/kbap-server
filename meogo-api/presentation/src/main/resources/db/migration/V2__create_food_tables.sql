-- food 컨텍스트: 음식 + 재료 + 다국어 번역(ko 원문 + 9개 대상 언어).
-- 공통 컬럼(status=EntityStatus 소프트삭제, created_at, updated_at)은 BaseEntity(@MappedSuperclass)에서 온다.
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

CREATE TABLE food_name_translation (                    -- 9개 대상 언어
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    food_id    BIGINT       NOT NULL,
    lang_code  VARCHAR(10)  NOT NULL,                   -- zh-Hans·en·ja·zh-Hant·vi·id·th·ru·es
    name       VARCHAR(255) NOT NULL,
    status     VARCHAR(20)  NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_fnt_food FOREIGN KEY (food_id) REFERENCES food(id),
    UNIQUE KEY uq_fnt (food_id, lang_code)
);

CREATE TABLE ingredient (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    korean_name VARCHAR(255) NOT NULL,                  -- ko 원문
    icon_ref    VARCHAR(500) NULL,
    status      VARCHAR(20)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
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
    UNIQUE KEY uq_int (ingredient_id, lang_code)
);

CREATE TABLE food_ingredient (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    food_id           BIGINT      NOT NULL,
    ingredient_id     BIGINT      NOT NULL,
    inclusion_percent INT         NOT NULL,             -- 0~100 (여러 레시피 기준 포함 확률)
    display_order     INT         NOT NULL,
    status            VARCHAR(20) NOT NULL,
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_fi_food FOREIGN KEY (food_id) REFERENCES food(id),
    CONSTRAINT fk_fi_ingredient FOREIGN KEY (ingredient_id) REFERENCES ingredient(id)
);

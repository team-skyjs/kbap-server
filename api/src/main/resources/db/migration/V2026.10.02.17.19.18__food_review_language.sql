-- KB-702: food_review 에 본문 언어 컬럼을 더한다. NULL 을 허용하며 기존 행은 NULL 로 남는다.
ALTER TABLE food_review
    ADD COLUMN language VARCHAR(10) NULL,
    ALGORITHM = INSTANT;

-- KB-808 (S1, 에픽 KB-807): 국내 가공식품 제품 마스터. HACCP 제품이미지 및 포장지표기정보 API(data.go.kr 15033307)를
-- 배치가 선별 품목유형(prdkind)별로 전체 순회해 복제·갱신한다. 행 = 품목보고번호 1개(실측 2,955행에서 중복 0).
-- 가산 전용 선행 스키마 — 엔티티·배치 코드는 dev 적용 확인 뒤 짝 PR 에서 매핑한다(batch 는 ddl-auto=validate).
-- 길이는 2026-10-20 실측 최대값(제품명 50·원재료 1,777·영양 2,397·알레르기 230·이미지 URL 78) 기준 여유 있게 잡았다.
-- normalized_name = 제품명에서 공백·괄호·'오리지널' 을 뺀 매칭용 이름(S4 이름 스캔 조회 인덱스).
-- maker_supplement = 제조원·판매원이 '알수없음' 일 때 식품영양성분DB(15127578) MAKER_NM 을 품목보고번호로 이어 채운 값.
-- maker_checked_at = 그 보완 조회를 한 시각(같은 행을 매일 다시 조회하지 않기 위함).
-- content_hash = 원본 필드 해시 — 증분 갱신에서 바뀐 행만 UPDATE 한다. last_seen_at = 마지막 순회에서 본 시각.
-- 이미지는 URL 만 저장한다(파일 복사·S3 업로드 없음).

CREATE TABLE processed_food_product
(
    id               BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    report_no        VARCHAR(20)               NOT NULL,
    product_name     VARCHAR(200)              NOT NULL,
    normalized_name  VARCHAR(200)              NOT NULL,
    product_gb       VARCHAR(20)               NULL,
    food_type        VARCHAR(200)              NULL,
    food_type_state  VARCHAR(200)              NULL,
    manufacturer     VARCHAR(300)              NULL,
    seller           VARCHAR(300)              NULL,
    maker_supplement VARCHAR(200)              NULL,
    maker_checked_at DATETIME(6)               NULL,
    barcode          VARCHAR(50)               NULL,
    capacity         VARCHAR(300)              NULL,
    raw_materials    TEXT                      NULL,
    allergens        VARCHAR(1000)             NULL,
    nutrients        TEXT                      NULL,
    image_url        VARCHAR(500)              NULL,
    meta_image_url   VARCHAR(500)              NULL,
    source           VARCHAR(30)               NOT NULL DEFAULT 'HACCP',
    content_hash     CHAR(64)                  NOT NULL,
    collected_at     DATETIME(6)               NOT NULL,
    last_seen_at     DATETIME(6)               NOT NULL,
    status           ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at       DATETIME(6)               NOT NULL,
    updated_at       DATETIME(6)               NOT NULL,
    CONSTRAINT uq_processed_food_product_report_no UNIQUE (report_no),
    INDEX idx_processed_food_product_normalized_name (normalized_name),
    INDEX idx_processed_food_product_barcode (barcode)
);

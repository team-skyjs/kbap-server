-- KB-983 스캔 결과 기록 — 스캔 요청 1건(사진 1장)마다 성공·실패, 앱이 띄우는 화면 종류, 매칭·미등록 요약을 남긴다.
-- 가산 전용 신규 테이블. 응답 경로 밖(비동기)에서 적재하고 영구 보관한다(정리 잡 없음 — 예진 결정 11/4).
-- 로그 성격이라 FK 를 두지 않는다(food_view_log·processed_food_signal 과 같음) — 부모 행 공유 잠금이 스캔 경로의 회원 잠금과 얽히지 않게.
-- uploaded_image_id 는 업로드 기록을 찾지 못한 요청(업로드 전 실패·성분표 OCR 텍스트 전용)에서 NULL 이다.

CREATE TABLE scan_result_log
(
    id                BIGINT                    NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id         BIGINT                    NOT NULL,
    uploaded_image_id BIGINT                    NULL,
    image_path        VARCHAR(512)              NULL,
    scan_mode         VARCHAR(32)               NOT NULL,
    outcome           VARCHAR(16)               NOT NULL,
    error_code        VARCHAR(32)               NULL,
    ui_kind           VARCHAR(32)               NULL,
    vision_kind       VARCHAR(32)               NULL,
    app_version       VARCHAR(32)               NULL,
    api_version       VARCHAR(16)               NULL,
    response_summary  JSON                      NULL,
    status            ENUM ('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    created_at        DATETIME(6)               NOT NULL,
    updated_at        DATETIME(6)               NOT NULL,
    INDEX idx_scan_result_log_image (uploaded_image_id, id),
    INDEX idx_scan_result_log_created (created_at)
);

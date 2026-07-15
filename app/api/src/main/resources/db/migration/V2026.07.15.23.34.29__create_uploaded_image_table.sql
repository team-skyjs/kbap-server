-- KB-138: 완료 검증을 통과한 업로드 이미지 기록. 스캔 등 소비 기능이 object_path 의 존재·소유를 확인한다.
-- object_path 는 도메인 없는 경로만 저장한다(CDN 도메인은 서버 설정). 공통 컬럼(status·created_at·updated_at)은 BaseEntity.
CREATE TABLE uploaded_image (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    member_id    BIGINT       NOT NULL,
    object_path  VARCHAR(512) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    status       ENUM('ACTIVE','DELETED') NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_uploaded_image_path (object_path),
    INDEX idx_uploaded_image_member (member_id),
    CONSTRAINT fk_uploaded_image_member FOREIGN KEY (member_id) REFERENCES member (id)
);

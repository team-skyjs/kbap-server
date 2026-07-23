# Data Model: 음식 이미지 비동기 생성

**Date**: 2026-07-24 | **Plan**: [plan.md](./plan.md)

## 신규 엔티티 (`:domain:food`, BaseEntity 상속, JPA 연관관계 없음)

### image_batch — 외부 제출 단위

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK (BaseEntity) | |
| openai_batch_id | VARCHAR(100) | NOT NULL, UNIQUE | OpenAI 배치 식별자 — 폴링 키 |
| batch_status | ENUM('SUBMITTED','COLLECTED','FAILED') | NOT NULL | 상태의 원천은 이 컬럼(OpenAI 아님) |
| prompt_version | VARCHAR(20) | NOT NULL | 제출 시점 프롬프트 버전(기록용) |
| model | VARCHAR(50) | NOT NULL | 예: gpt-image-2 |
| submitted_at | DATETIME | NOT NULL | |
| collected_at | DATETIME | NULL | COLLECTED/FAILED 마감 시각 |

인덱스: `idx_image_batch_status (batch_status)` — 폴링은 SUBMITTED만 조회.

### image_batch_item — 배치 내 음식 1건

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK (BaseEntity) | |
| batch_id | BIGINT | NOT NULL, FK→image_batch.id (Flyway 강제, JPA 연관 없음) | Long id 값 참조 |
| food_id | BIGINT | NOT NULL, FK→food.id | = JSONL custom_id — 결과 매칭이 DB 조인으로 끝남 |
| item_status | ENUM('PENDING','DONE','FAILED') | NOT NULL | |
| file_name | VARCHAR(500) | NULL | 저장된 스토리지 키(DONE 시) |
| error_msg | VARCHAR(1000) | NULL | FAILED 사유 |

인덱스: `idx_image_batch_item_batch (batch_id)`, `idx_image_batch_item_food_status (food_id, item_status)` — 제출 후보 제외 조건(`NOT IN PENDING`) 및 재회수 조회용.

## 기존 엔티티 변경

### food

- `content_status` ENUM에 **TEXT_READY** 추가: `('INCOMPLETE','TEXT_READY','PENDING_REVIEW','READY')` — Flyway MODIFY COLUMN. 기존 행 값 변경 없음.
- `imageRef`(기존 컬럼): 회수 시 스토리지 키로 갱신. "이미지 필요" 판단의 단일 축.
- `Food.transitionToPendingReviewIfComplete()` → **수렴 전이 함수로 재작성**: 칼럼 상태로 목표 상태를 계산. 콘텐츠 배치·이미지 회수 양쪽이 같은 함수 호출.

## 상태 전이

### FoodContentStatus (수렴표 — 순서 무관)

| 텍스트 4조건(번역·설명·설명번역·기피성분) | imageRef | 결과 |
|---|---|---|
| 미완 | 무관 | INCOMPLETE 유지 (이미지 먼저 오면 imageRef만 세팅) |
| 완료 | 없음 | TEXT_READY |
| 완료 | 있음 | PENDING_REVIEW (TEXT_READY 건너뜀) |

PENDING_REVIEW → READY는 검수 승인(KB-223, 본 스펙 범위 외). READY/PENDING_REVIEW에서 후퇴 없음.

### ImageBatchStatus

`SUBMITTED → COLLECTED` (완료분 전 항목 처리 후) / `SUBMITTED → FAILED` (배치 단위 failed/expired). 종결 상태에서 전이 없음.

### ImageBatchItemStatus

`PENDING → DONE` (S3 저장 + imageRef 갱신 성공) / `PENDING → FAILED` (항목별 error 또는 배치 failed/expired). 회수 도중 중단 시 PENDING 잔존 → 다음 틱에 재처리(멱등). FAILED 항목의 food는 imageRef 부재이므로 다음 제출에 자동 재포함.

## 회수 트랜잭션 경계

- 배치 상태 GET·JSONL 스트리밍·S3 put: 트랜잭션 밖.
- 항목 1건 처리마다 짧은 트랜잭션: `food.imageRef 갱신 + 수렴 전이 + item DONE` (원자). 항목 단위 커밋이라 중단 시에도 처리분은 유지.
- 전 항목 처리 후 별도 트랜잭션으로 배치 COLLECTED 마감.

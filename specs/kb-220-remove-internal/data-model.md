# Data Model: internal 제거

**DB 스키마·엔티티 필드 변경 없음.** 이 기능은 코드 가시성과 배선만 바꾼다 — Flyway 마이그레이션 없음, 컬럼·인덱스·제약 불변.

## 가시성 변경 대상 (코드 구조)

| 구분 | 대상 | 변경 |
|------|------|------|
| 리포지토리 | `AvoidanceSubstanceJpaRepository` · `BookmarkJpaRepository` · `MemberJpaRepository` · `ScanHistoryJpaRepository` · `UploadedImageJpaRepository` · `LlmCallCostJpaRepository` · `FoodJpaRepository` · `FoodJpaRepositoryCustom` · `FoodJpaRepositoryCustomImpl` (9) | `internal` → public |
| 도메인 서비스 생성자 | `BookmarkService` · `MemberService` · `ScanService` · `ImageUploadService` · `LlmCallCostService` · `FoodService` (6 — 삭제되는 창구 2개는 제외, avoidance 도메인엔 잔존 대상 없음) | `internal constructor` → 일반 생성자 |
| JPA 엔티티 | 전 도메인 | 변경 없음(이미 public) — 전수 확인만 |

## 삭제 대상

| 클래스 | 모듈 | 소비처 재배선 |
|--------|------|--------------|
| `FoodContentBatchService` | `:domain:food` | `:app:batch` content 파이프라인이 `FoodJpaRepository` 직접 사용, 진행 저장은 배치 소유 `TransactionTemplate(REQUIRES_NEW)` (research D1) |
| `AvoidanceCatalogService` | `:domain:avoidance` | `HomeApplicationService` 가 `AvoidanceSubstanceJpaRepository.findByCodeIn` 직접 사용, 빈 컬렉션 가드는 호출부 소유 (research D3) |

## 상태 전이 (불변 확인)

`Food.contentStatus`: `INCOMPLETE → READY` 전이는 엔티티 메서드 `transitionToReadyIfComplete()` 소유 — 이번 변경으로 이동하지 않는다. 호출 지점만 창구 서비스에서 배치 라이터로 바뀐다.

# Data Model: 배치 완성 콘텐츠 PENDING_REVIEW 전이

## FoodContentStatus (enum, `:domain:food`)

| 값 | 의미 | 사용자 노출 |
|----|------|------------|
| `INCOMPLETE` | 콘텐츠 4작업(음식명 번역·설명·사진·기피성분) 미완성 — 배치 처리 대상 | ✗ |
| `PENDING_REVIEW` *(신규)* | 4작업 완비 — 관리자 검수 대기 | ✗ |
| `READY` | 검수 통과(또는 기능 도입 전 완성) — 노출 가능 | ✓ |

## 상태 전이

```text
INCOMPLETE ──(배치: 4작업 완비, transitionToPendingReviewIfComplete)──▶ PENDING_REVIEW

PENDING_REVIEW ──(관리자 승인 — 후속 브랜치)──▶ READY
PENDING_REVIEW ──(관리자 반려 — 후속 브랜치)──▶ INCOMPLETE
```

- 이 브랜치가 구현하는 전이는 **INCOMPLETE → PENDING_REVIEW 하나**다.
- `transitionToPendingReviewIfComplete()` 는 `contentStatus != INCOMPLETE` 이면 즉시 true(할 일 없음), INCOMPLETE 인데 미완비면 false·상태 유지, 완비면 PENDING_REVIEW 전이 후 true.
- 완비 판정(불변): `!needsImage() && !needsDescription() && !needsNameTranslations() && !needsDescriptionTranslations() && !needsAvoidanceMapping() && spiciness != SPICINESS_UNASSESSED` — 기피성분 null 센티널 판정은 KB-209 기구현 그대로.

## 컬럼 (`food.content_status`)

- 현행: `enum('INCOMPLETE','READY') NOT NULL DEFAULT 'READY'` (init_schema)
- 변경: `enum('INCOMPLETE','PENDING_REVIEW','READY') NOT NULL DEFAULT 'READY'` — Flyway `V2026.07.23.<HH.mm.ss>__food_content_status_pending_review.sql`
- 엔티티 `@Column(columnDefinition = "ENUM('INCOMPLETE','PENDING_REVIEW','READY')")` 동기화(테스트 schema-generation 경로 커버)
- 기존 행 값 변경 없음 — READY 는 READY 유지(FR-004), 데이터 마이그레이션 불필요

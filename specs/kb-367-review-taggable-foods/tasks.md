# Tasks: 리뷰 작성 시 태그 가능한 음식 목록 조회 API

**Input**: Design documents from `/specs/kb-367-review-taggable-foods/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/scanned-foods.md

**Tests**: Test-First (헌법 원칙 I) — 실패 테스트(Red) 선작성 후 구현(Green).

**Organization**: 단일 스토리(US1) 기능 — Setup·Foundational 없음(스키마 무변경, 기존 기능 확장). 신규 파일은 요청 DTO·테스트뿐이고 나머지는 기존 파일 확장.

## Phase 1: User Story 1 — 리뷰 작성자가 자기 스캔 음식 중에서 태그를 고른다 (P1)

**Goal**: `GET /api/foods/scanned`(회원 전용) — 본인 스캔 이력 매칭 READY 음식을 중복 제거·마지막 스캔 시점 내림차순, 커서 페이징(20)·keyword 필터로 내린다.

**Independent Test**: A→B→A 스캔 회원 조회 → [A, B]. 스캔 없음 → 빈 목록. 비회원 → 401. 21건 이상 → 커서 연결. keyword → 스캔 범위 안에서만 매칭.

- [x] T001 [US1] **Red**: `api/src/test/kotlin/com/kbap/api/food/FoodScannedListControllerTest.kt` 신규 작성(BehaviorSpec + MySqlContainerConfig + MockMvc, scan_history 시드는 SQL INSERT) — 시나리오: (1) A→B→A 스캔 → [A, B] 중복 없이 최신 스캔순, (2) 스캔 이력 없음 → 빈 목록, (3) READY 아닌 음식·food_id null(미매칭) 스캔 제외, (4) 비회원 401, (5) 스캔 음식 21개 → 첫 페이지 20건+nextCursor, 커서로 나머지 1건·hasNext=false, (6) keyword 필터 — 스캔 음식만 매칭·스캔 안 한 음식은 키워드 일치해도 제외, (7) lang 누락 400, (8) 비정상 커서(`abc`) 400. 실행해 **실패(Red) 확인**(404/401)
- [x] T002 [P] [US1] 스캔 리포지토리 쿼리 추가 — `common/src/main/kotlin/com/kbap/common/domain/scan/ScanHistoryJpaRepository.kt`: data-model.md 의 파생 테이블 native 쿼리 `findScannedFoodPageIds(memberId, kw, jsonPath, cursorLastScannedAt, cursorFoodId, size)` + 커서 재계산 `findLastScannedAt(memberId, foodId): LocalDateTime?`
- [x] T003 [P] [US1] 요청 DTO 신규 — `api/src/main/kotlin/com/kbap/api/food/FoodScannedRequest.kt`: `lang`(필수, 기존 검증 수위)·`cursor`(옵션)·`keyword`(옵션) — `FoodSearchRequest` 동형, keyword 만 옵션
- [x] T004 [US1] 서비스 조합 — `api/src/main/kotlin/com/kbap/api/scan/ScanService.kt`: `getScannedFoodPage(memberId, keyword, lang, cursor)` `@Transactional(readOnly = true)` — `SearchKeywordParser`·jsonPath(기존 검색과 동일 구성) 준비, 커서 있으면 `findLastScannedAt` 재계산(null → `BusinessException` 400, 기존 비정상 커서 규약), `PAGE_SIZE + 1` 조회로 hasNext·nextCursor(마지막 foodId) 판정, ids 순서 보존 음식 로드(associateBy+mapNotNull)까지 담아 반환
- [x] T005 [US1] 컨트롤러·스웨거 — `api/src/main/kotlin/com/kbap/api/food/FoodController.kt` 에 `@GetMapping("/scanned")` + `@AuthMemberId` + 기존 `toPage` 재사용, `api/src/main/kotlin/com/kbap/api/food/FoodApi.kt` 에 오퍼레이션 문서(회원 전용·중복 제거·최신 스캔순·keyword 규칙·커서 규약)
- [x] T006 [US1] JWT 보호 경로 등록 — `api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt` `addUrlPatterns` 에 `/api/foods/scanned` 정확 패턴 추가(foods 나머지는 비회원 공개 유지)
- [x] T007 [US1] **Green 확인**: `./gradlew :api:test --tests "com.kbap.api.food.FoodScannedListControllerTest"` — 전 시나리오 그린, 실패 시 쿼리·조립 보완

**Checkpoint**: US1 완결 — 기능 전체가 이 스토리 하나다.

---

## Phase 2: Polish & Cross-Cutting

- [x] T008 기존 회귀 확인 — `./gradlew :api:test --tests "com.kbap.api.food.*" --tests "com.kbap.api.home.*"` (음식 목록·검색·홈 recentScans 무회귀)
- [x] T009 전체 빌드 그린 — `./gradlew build` (OpenAPI 스냅샷·ArchUnit 포함). 필요시 quickstart.md 수동 검증

---

## Dependencies

```text
US1: T001(Red) → T002 ∥ T003 → T004 → T005 → T006 → T007(Green)
  → Polish: T008 → T009
```

- [P]: T002∥T003 (다른 파일, 상호 독립).
- T004 는 T002 의 쿼리 시그니처에, T005 는 T003·T004 에 의존.

## Implementation Strategy

- **MVP = US1 전체** — 단일 스토리라 그대로 완결.
- 보호 경로 등록(T006)이 빠지면 T007 에서 전 시나리오 401 로 즉시 드러난다 — Red 테스트의 비회원 401 시나리오와 등록 여부가 계약의 양면.
- 커밋 단위: 단일 feature 커밋(파일 겹침 큼).

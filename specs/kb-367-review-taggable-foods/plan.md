# Implementation Plan: 리뷰 작성 시 태그 가능한 음식 목록 조회 API

**Branch**: `kb-367-review-taggable-foods` | **Date**: 2026-08-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-367-review-taggable-foods/spec.md`

## Summary

`GET /api/foods/scanned`(회원 전용)를 추가해 본인 스캔 이력에 매칭된 READY 음식을 중복 제거·마지막 스캔 시점 내림차순으로 내린다. `ScanHistoryJpaRepository` 의 기존 `findRecentReadyFoodIds` 규칙을 파생 테이블 + `(last_scanned_at, food_id)` keyset 페이징으로 확장한 native 쿼리 하나가 핵심이고, 커서는 기존 Long(foodId) 계약을 유지하며 서버가 last_scanned_at 을 보조 쿼리로 재계산한다. keyword 필터는 기존 음식 검색 매칭 규칙을 복제하고, 응답은 기존 `Page<FoodSummaryResponse>` 조립(`FoodController.toPage`)을 재사용한다. 스키마 변경 없음.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (기존 스택)

**Primary Dependencies**: Spring Boot 4.1 (web·data-jpa) — 신규 의존 없음

**Storage**: MySQL — **스키마 변경 없음**(읽기 전용, 기존 `scan_history`·`food`)

**Testing**: Kotest BehaviorSpec + MySQL Testcontainers(@SpringBootTest+MockMvc)

**Target Platform**: `:api` 모듈만 (신규 native 쿼리는 `:common` 스캔 리포지토리)

**Project Type**: web-service — 기존 모놀리스 내 조회 API 추가

**Performance Goals**: 회원당 스캔 이력 수십~수백 건 규모 — 기존 인덱스(`idx_scan_history_recent`)로 충분

**Constraints**: 홈 recentScans 와 제외·정렬 규칙 일치 · 커서 계약은 기존 Long 유지 · JWT 보호 경로 등록 필수(FR-004)

**Scale/Scope**: 쿼리 2개(페이지·커서 재계산) + 서비스 메서드 1개 + 컨트롤러 엔드포인트 1개 + 테스트 — 소형(SP 1)

## Constitution Check

*GATE: 통과(위반 없음). Phase 1 설계 후 재평가 — 동일.*

- **I. Test-First**: 신규 컨트롤러 테스트를 Red 로 선작성 후 구현. 통과.
- **II. Bounded Contexts**: scan 도메인(이력 쿼리)·food 도메인(로드)·api 기능 패키지(조립) — 기존 허용 방향 그대로(홈이 이미 같은 조합). native SQL join 은 패키지 의존을 만들지 않는다(`findRecentReadyFoodIds` 선례). 통과.
- **III. Layered Dependency Direction**: api → common 단방향 유지. 통과.
- **IV. Persistence Ownership**: 쿼리는 스캔 도메인 리포지토리 소유, 소비 계층(ScanService·FoodController)이 직접 참조 — 창구 서비스 없음. `@Transactional(readOnly = true)` 명시. 통과.
- **V. Domain Content Language Policy**: `lang` 필수·en 폴백 — 기존 음식 목록 규칙 재사용. 검증은 요청 DTO 소유. 통과.

## Project Structure

### Documentation (this feature)

```text
specs/kb-367-review-taggable-foods/
├── plan.md              # This file
├── research.md          # Phase 0 — 결정 7건(경로·keyword 옵션·쿼리·커서·조립·보호 경로·인덱스)
├── data-model.md        # Phase 1 — 스키마 무변경·파생 뷰 쿼리
├── quickstart.md        # Phase 1 — 수동 검증 시나리오
├── contracts/
│   └── scanned-foods.md # GET /api/foods/scanned 계약
└── tasks.md             # /speckit-tasks 산출(이 커맨드 아님)
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/scan/ScanHistoryJpaRepository.kt  # 페이지 ids native 쿼리 + 커서 last_scanned_at 재계산 쿼리
api/src/main/kotlin/com/kbap/api/scan/ScanService.kt                            # getScannedFoodIdPage — size+1 조회·hasNext·커서 재계산·400 처리
api/src/main/kotlin/com/kbap/api/food/FoodController.kt                         # GET /api/foods/scanned — @AuthMemberId·기존 toPage 재사용
api/src/main/kotlin/com/kbap/api/food/FoodApi.kt                                # swagger 오퍼레이션
api/src/main/kotlin/com/kbap/api/food/FoodScannedRequest.kt                     # lang(필수)·cursor·keyword(옵션)
api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt                       # JWT 보호 경로에 /api/foods/scanned 추가
api/src/test/kotlin/com/kbap/api/food/FoodScannedListControllerTest.kt          # 신규 BehaviorSpec 통합 테스트
```

**Structure Decision**: 신규 파일은 요청 DTO·테스트뿐 — 나머지는 기존 파일 확장. ids 순서 보존 로드(HomeService 의 associateBy+mapNotNull 패턴)와 `FoodSummaryView` 매핑·`toPage`(북마크·평점 일괄) 재사용으로 응답 계약 이중화를 막는다.

## 구현 노트 (Phase 1 설계 확정)

- 페이지 쿼리는 `:size = PAGE_SIZE + 1` 로 불러 hasNext 판정(기존 패턴). keyword 는 `SearchKeywordParser` 재사용, jsonPath 는 기존 검색과 동일하게 lang 으로 구성.
- 커서 흐름: `cursor(foodId)` → 보조 쿼리로 본인 기준 `max(created_at)` 재계산 → null 이면 400(비정상 커서, 기존 FOOD-002 규약) → keyset `(last_scanned_at, food_id)` 적용.
- `FoodScannedRequest` 는 `FoodSearchRequest`/`FoodBrowseRequest` 와 동형(keyword 만 옵션) — 검증 애너테이션 동일 수위.
- 보호 경로 등록이 계약의 절반이다(미등록 = 전 시나리오 401 함정 ↔ 등록 = 비회원 401 계약). 테스트에 비회원 401 시나리오 필수.
- 홈 recentScans(`getRecentReadyFoodIds`)는 손대지 않는다 — 규칙만 일치시키고 쿼리는 분리 유지(limit 전용 vs 페이징).

## Complexity Tracking

> 위반 없음 — 해당 없음.

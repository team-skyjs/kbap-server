# Tasks: 스캔 2.0 응답 구조 통일 — similarFood 제거

**Input**: Design documents from `/specs/kb-343-remove-similar-food/`

**Prerequisites**: plan.md, spec.md, research.md, contracts/scan-v2-no-similar-food.md

**Tests**: Test-First (헌법 원칙 I) — 제거 기능의 Red = "없어야 할 필드가 아직 있음".

**Organization**: 단일 스토리(US1) 순삭제 — Setup·Foundational 없음.

## Phase 1: User Story 1 — 비매칭 메뉴가 유사 음식 없이 그대로 내려온다 (P1)

**Goal**: v2 응답에서 similarFood 키 소멸, 비매칭은 1.0 원칙 그대로, 유사 음식 검색 미호출. v2 빈 추출 400(KB-330)은 유지.

**Independent Test**: 미등록 메뉴 v2 스캔 → similarFood 키 부재 + 정제 한국어명·가격·UNKNOWN 유지.

- [x] T001 [US1] **Red**: `api/src/test/kotlin/com/kbap/api/scan/ScanControllerTest.kt` 에 시나리오 추가 — 미등록 메뉴 v2 스캔 시 `results[0].similarFood` 가 `doesNotExist`(키 자체 부재)이고 `matched=false`·`riskLevel=UNKNOWN`·정제 한국어명·가격 유지. 실행해 **실패 확인**(현재는 similarFood: null 로 직렬화됨)
- [x] T002 [US1] 응답·도메인 제거 — `api/src/main/kotlin/com/kbap/api/scan/ScanResult.kt`(SimilarFood 클래스·similarFood 필드), `ScanV2Response.kt`(SimilarFoodResponse·similarFood 필드·from 조립·avoidances description 의 similarFood 구절), `ScanV2Api.kt`(swagger 의 similarFood 언급) 정리
- [x] T003 [US1] 서비스 제거 — `api/src/main/kotlin/com/kbap/api/scan/ScanService.kt`: similarFoodResolver 주입·resolveSimilarFoods·toSimilarFood 제거, `similarFoodFallback` → `requireDetectedMenu` 개명(v2 빈 추출 400 게이트만 유지)
- [x] T004 [US1] 파일 삭제·정리 — `api/src/main/kotlin/com/kbap/api/scan/SimilarFoodResolver.kt` 삭제, `api/src/main/resources/application.yml` 의 `kbap.vector.similarity-threshold` 미참조 정리(vector 검색·적재 seam·config 는 존치)
- [x] T005 [US1] 테스트 전환·**Green** — `ScanControllerTest.kt` 의 similarFood 의존 시나리오(603~607) 제거·전환, `FakeSimilarFoodSearch.kt` 삭제 또는 존치 판단(seam 빈이 테스트 컨텍스트에 필요하면 유지), 기존 `similarFood { value(null) }` 단언을 `doesNotExist` 로. `./gradlew :api:test --tests "com.kbap.api.scan.ScanControllerTest"` 그린

## Phase 2: Polish

- [x] T006 전체 빌드 그린 — `./gradlew build` (OpenAPI 스냅샷·ArchUnit 포함)

## Dependencies

```text
T001(Red) → T002 ∥ T003 → T004 → T005(Green) → T006
```

## Implementation Strategy

- v2 빈 추출 400 이 유일한 함정 — 플래그 개명으로 격리(R2). 벡터 seam·config·배치는 한 줄도 안 건드린다(R1).
- FakeSimilarFoodSearch 는 seam 빈(TextEmbeddingClient·FoodVectorSearcher)을 테스트 컨텍스트에 공급하는 역할이 남아 있으면 존치 — 컨텍스트 부팅 실패 여부로 판단.
- 커밋 단위: 단일 feature 커밋.

# Implementation Plan: 음식 리스트·상세 조회 응답에 북마크 여부(bookmarked) 포함

**Branch**: `kb-153-food-bookmark-flag` | **Date**: 2026-07-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-153-food-bookmark-flag/spec.md`

## Summary

음식 리스트·상세(+구조 공유로 검색·북마크 목록) 응답에 `bookmarked: Boolean` 을 추가한다 — 비회원 항상 false, 회원은 실제 북마크 여부. **조합 지점은 `:app:api` 컨트롤러**다: `:domain:bookmark` 가 `:domain:food` 에 의존하므로(북마크 목록이 음식 요약 재사용) food 쪽에 북마크 조회를 넣으면 순환이 생긴다. 대신 `BookmarkService` 에 일괄 조회 창구 `findBookmarkedFoodIds(memberId: Long?, foodIds): Set<Long>` 하나를 추가하고(비회원 null → emptySet — 규칙이 서비스 한 곳에), 컨트롤러가 food 조회 결과와 합쳐 API 응답 DTO 에 채운다. 도메인 dto 무변경, DB·Flyway 무변경(bookmark 테이블 기존재), 신규 모듈 의존 0(app:api 는 이미 양쪽 도메인 의존). 페이지당 IN 쿼리 1회로 N+1 없음.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·data-jpa), springdoc-openapi — 신규 의존성 0

**Storage**: MySQL — **스키마·Flyway 무변경** (`bookmark(member_id, food_id)` 테이블 기존재, 소프트삭제는 `BaseEntity.status` + `@SQLRestriction` 자동 적용)

**Testing**: Kotest BehaviorSpec + MockMvc 통합(MySQL Testcontainers). 회원 인증은 기존 `BookmarkControllerTest` 패턴(`TokenIssuer.issueAccessToken` → `Authorization: Bearer`) 재사용

**Target Platform**: `:app:api` web bootJar (`:app:batch` 범위 밖)

**Project Type**: web-service (모듈러 모놀리스)

**Performance Goals**: 리스트 페이지(20건)당 북마크 확인 쿼리 1회(`food_id IN`) — 항목별 조회 금지

**Constraints**: 도메인 의존 그래프 무변경(food→bookmark 역의존 금지 — 순환), 기존 응답 필드·페이지네이션·언어 처리 무변경

**Scale/Scope**: 신규 public 메서드 1(BookmarkService) + 리포지토리 derived query 1 + API DTO 필드 2 + 컨트롤러 조합 4곳(browse·search·detail·북마크 목록)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS | MockMvc 통합 테스트(비회원 false·회원 true/false·취소 반영·북마크 목록 true)를 먼저 작성해 Red(필드 부재) 확인 → 구현 Green |
| II. Bounded Contexts | PASS | 신규 도메인 간 의존 0 — 기존 `bookmark → food(api)` 그대로. 크로스 도메인 참조는 foodId(Long) 값. member 리프 유지 |
| III. Layered Dependency Direction | PASS | 조합은 `:app:api` 컨트롤러(부트앱이 도메인 서비스 직접 호출 — 허용 패턴). `:application` 승격 불필요 — 도메인 간 **순환**이 아니라 부트앱 레벨 병합이다 |
| IV. Persistence Encapsulation | PASS | 신규 derived query 는 `internal` 리포지토리에, 외부 창구는 `BookmarkService.findBookmarkedFoodIds` 하나 |
| V. Domain Content Language Policy | PASS | 언어 정책 무관(boolean 필드) — 기존 lang 처리 무변경 |

게이트 위반 없음 — Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-153-food-bookmark-flag/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── is-bookmarked-response-contract.md
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
domain/bookmark/src/main/kotlin/com/kbap/domain/bookmark/
├── BookmarkService.kt                 # [수정] findBookmarkedFoodIds(memberId: Long?, foodIds): Set<Long> 추가
└── BookmarkJpaRepository.kt           # [수정] findByMemberIdAndFoodIdIn(memberId, foodIds) derived query 추가 (internal)

app/api/src/main/kotlin/com/kbap/app/api/
├── food/FoodSummaryResponse.kt        # [수정] bookmarked: Boolean 필드 + from(view, bookmarked)
├── food/FoodDetailResponse.kt         # [수정] bookmarked: Boolean 필드(@Schema) + from(result, bookmarked)
├── food/FoodController.kt             # [수정] BookmarkService 주입, browse·search·detail 에서 병합
├── food/FoodApi.kt                    # [수정] 응답 설명에 bookmarked 언급(비회원 false 고정)
├── bookmark/BookmarkController.kt     # [수정] 목록 항목 from(view, bookmarked = true) — 정의상 전부 true
└── bookmark/BookmarkApi.kt            # [수정] 목록 응답 설명 갱신

app/api/src/test/kotlin/com/kbap/app/api/
├── food/FoodListControllerTest.kt     # [보강] 비회원 false·회원 true/false 혼재 케이스
├── food/FoodDetailControllerTest.kt   # [보강] 비회원 false·회원 true·취소 후 false 케이스
├── food/FoodSearchControllerTest.kt   # [보강] 검색도 동일 규칙(구조 공유 결과) 케이스
└── bookmark/BookmarkControllerTest.kt # [보강] 목록 항목 bookmarked=true 검증

domain/bookmark/src/test/kotlin/com/kbap/domain/bookmark/
└── BookmarkServiceTest.kt             # [보강] findBookmarkedFoodIds — null 회원 emptySet·소프트삭제 제외·일괄 조회
```

**Structure Decision**: 조합은 컨트롤러(4곳)에서 한다. `FoodSummaryResponse` 가 리스트·검색·북마크 목록에 공유되므로 필드는 세 API 에 함께 생긴다 — 검색은 spec Assumption 대로 동일 규칙 적용, 북마크 목록은 정의상 상수 true(쿼리 불필요). 도메인 dto(`FoodSummaryView`·`GetFoodDetailResult`)는 건드리지 않는다 — 북마크는 food 도메인의 관심사가 아니고, 넣으려면 순환이 생긴다.

## 구현 축 (Phase 2 tasks 의 뼈대)

1. **Red — MockMvc 테스트 선행**: 리스트·상세·검색·북마크 목록에 bookmarked 기대 케이스 작성(필드 부재로 실패 확인). 회원 시나리오는 기존 토큰 발급 헬퍼 + 북마크 등록 API 재사용.
2. **Green — 도메인 창구 + 컨트롤러 병합**: `BookmarkJpaRepository.findByMemberIdAndFoodIdIn` → `BookmarkService.findBookmarkedFoodIds`(null 회원 → emptySet, `@Transactional(readOnly = true)`) → 컨트롤러 4곳 병합 + DTO 필드 추가.
3. **도메인 단위 보강**: `BookmarkServiceTest` 에 일괄 조회·null 회원·소프트삭제(unbookmark 후 제외) 검증.
4. **문서**: `FoodDetailResponse` `@Schema`·`FoodApi`/`BookmarkApi` 설명에 "비회원은 항상 false" 명시.

## Complexity Tracking

해당 없음 — 게이트 위반 0건.

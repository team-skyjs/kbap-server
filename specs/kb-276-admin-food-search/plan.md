# Implementation Plan: 관리자 음식 목록 음식명 검색

**Branch**: `kb-276-admin-food-search` | **Date**: 2026-08-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-276-admin-food-search/spec.md`

## Summary

관리자 음식 목록 화면(`GET /admin/foods/list`)에 검색어 파라미터 `q`를 추가한다.
`AdminFoodService.getFoodPage`가 검색어를 받아 음식명(`koreanName`) 부분 일치로 필터링하고
(`FoodJpaRepository`에 Spring Data 파생 쿼리 추가), 화면의 페이지 이동·상세·수정 링크와
수정 후 redirect 가 `q`를 유지한다. 빈 결과 안내 + 검색 초기화 링크를 템플릿에 추가한다.
DB 스키마 변경 없음.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (기존 스택 그대로)

**Primary Dependencies**: Spring Boot 4.1 (web·data-jpa·Thymeleaf 서버 렌더링 관리자 화면)

**Storage**: MySQL — 기존 `food` 테이블 조회만. 스키마 변경·Flyway 마이그레이션 없음

**Testing**: Kotest BehaviorSpec + `@SpringBootTest`/MockMvc + MySQL Testcontainers (기존 `AdminFoodServiceTest`·`AdminFoodListControllerTest` 확장)

**Target Platform**: `:api` bootJar 의 관리자 화면 (`com.kbap.api.admin`)

**Project Type**: 기존 모듈러 모놀리스의 web 기능 확장

**Performance Goals**: 수천 건 규모 목록에서 검색 결과 즉시(기존 목록 조회와 동등) 응답 — `koreanName LIKE '%q%'` 페이지 조회로 충분(별도 인덱스·풀텍스트 검색 불필요, research.md 참조)

**Constraints**: 기존 페이지 크기(200)·정렬(id DESC)·화면 구조 불변. 검색어는 redirect URL 에 실리므로 URL 인코딩 필수

**Scale/Scope**: 파일 4개 내외 수정(서비스·컨트롤러·리포지토리·템플릿) + 테스트 2개 확장

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS (계획) | 서비스 검색 필터링·컨트롤러 `q` 유지를 실패 테스트로 먼저 작성(Red) 후 구현. 기존 BehaviorSpec 테스트 클래스 확장 |
| II. Bounded Contexts | PASS | 변경은 `com.kbap.api.admin`(관리자 기능 패키지)과 소유 도메인 패키지 `common.domain.food`(리포지토리 파생 쿼리 1개)에 한정. 도메인 간 의존 변화 없음 |
| III. Layered Dependency Direction | PASS | api → common 방향 그대로. 신규 모듈·seam 없음 |
| IV. Persistence Ownership | PASS | 파생 쿼리는 소유 도메인의 `FoodJpaRepository`에 추가. 관리자 서비스의 리포지토리 직접 사용은 기존 허용 패턴(창구 서비스 금지). `@Transactional(readOnly = true)` 기존 유지 |
| V. Language Policy | N/A | 관리자 화면은 한국어 전용, `lang` 파라미터 없음. 음식 콘텐츠 번역 정책과 무관 |

관리자 로직 분리 원칙(admin 전용 서비스, 공용 도메인 서비스 오염 금지)도 충족 — 변경은 `AdminFoodService`에만.

## Project Structure

### Documentation (this feature)

```text
specs/kb-276-admin-food-search/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── admin-food-list-page.md
└── tasks.md             # Phase 2 output (/speckit-tasks — not created here)
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/food/
└── FoodJpaRepository.kt                      # [수정] findByKoreanNameContaining 파생 쿼리 추가

api/src/main/kotlin/com/kbap/api/admin/
├── AdminFoodService.kt                       # [수정] getFoodPage(page, query) — blank→전체, 값→부분 일치
└── AdminFoodPageController.kt                # [수정] foodList q 파라미터 + updateFood redirect q 유지(인코딩)

api/src/main/resources/templates/admin/
└── food-list.html                            # [수정] 검색 폼·q 유지 링크·빈 결과 안내·초기화 링크

api/src/test/kotlin/com/kbap/api/admin/
├── AdminFoodServiceTest.kt                   # [확장] 검색어 유무별 getFoodPage 검증
└── AdminFoodListControllerTest.kt            # [확장] q 전달·유지·redirect 검증
```

**Structure Decision**: 신규 파일 없이 기존 관리자 기능 패키지 4개 파일 수정 + 테스트 2개 확장.
스키마 변경이 없으므로 Flyway 마이그레이션도 없다.

## Complexity Tracking

> Constitution Check 위반 없음 — 해당 없음.

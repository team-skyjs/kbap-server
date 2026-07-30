# Implementation Plan: 관리자 음식 수정 안정성 — 편집 모드 토글·상태 자동 전이

**Branch**: `kb-260-admin-food-edit-safety` | **Date**: 2026-07-30 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-260-admin-food-edit-safety/spec.md`

## Summary

관리자 음식 상세 모달(`admin/food-list.html`)을 기본 읽기 전용으로 바꾸고, 편집은 `edit` 쿼리 파라미터 기반 서버 렌더 토글로만 활성화한다(US1). 저장 시 `AdminFoodService.updateFood`가 관리자가 고른 `contentStatus`를 그대로 덮어쓰는 대신, 기존 도메인 전이 규칙 `Food.transitionByContentState()`를 저장 직후 호출해 검수 이전 상태(INCOMPLETE·PENDING_IMAGE)를 완성도 기준으로 자동 보정한다 — 검수 단계(PENDING_REVIEW·READY)는 전이 메서드의 sticky 의미론이 수동 지정을 그대로 보존한다(US2). 신규 판정 로직·JS·서버 상태 없이 기존 조각 재사용으로 끝낸다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Gradle toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·thymeleaf·data-jpa), 기존 `:api` admin SSR 스택

**Storage**: MySQL (Flyway 스키마 — 이번 기능은 스키마 변경 없음, 통합 테스트는 MySQL Testcontainers)

**Testing**: Kotest BehaviorSpec(given/when/then 한국어) + `@SpringBootTest`/MockMvc — 기존 `AdminFoodListControllerTest`·`AdminFoodServiceTest` 확장

**Target Platform**: 관리자 SSR 웹 페이지 (`/admin/foods/list`, 세션 인증 `AdminPageAuthInterceptor` 기존 그대로)

**Project Type**: 모듈러 모놀리스 — 변경은 `:api`(admin 기능 패키지 + Thymeleaf 템플릿)에 국한, `:common`은 무변경(기존 `Food.transitionByContentState()` 재사용)

**Performance Goals**: 해당 없음(소수 관리자 사용, 페이지당 200건 목록 기존 유지)

**Constraints**: JS 프레임워크·클라이언트 상태 도입 금지(기존 SSR 순수 HTML 패턴 유지), DB 스키마·공용 API(`/api/v1/**`) 무변경

**Scale/Scope**: 화면 1종(food-list 모달) + 컨트롤러 GET 파라미터 1개 + 서비스 저장 경로 1곳 + 테스트

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS | 편집 토글 렌더링·상태 자동 보정 시나리오를 실패 테스트로 먼저 작성(Red 확인) 후 구현. spec FR-007 이 명시 |
| II. Bounded Contexts | PASS | 관리자 조합 로직은 `com.kbap.api.admin`(Admin*Service)에 유지 — 공용 도메인 서비스 오염 없음. 상태 전이 규칙은 도메인 모델 `Food` 소유 그대로 재사용(새 판정 규칙 미도입) |
| III. Layered Dependency Direction | PASS | `:api` → `:common` 단방향 유지. 신규 모듈·의존 없음 |
| IV. Persistence Ownership | PASS | `AdminFoodService`가 `FoodJpaRepository` 직접 사용 + 명시적 `@Transactional` + dirty checking — 기존 구조 그대로, 창구 서비스 신설 없음 |
| V. Domain Content Language Policy | PASS | 표시 언어·번역 정책 무관(관리자 한국어 UI). 콘텐츠 데이터 계약 무변경 |

**Post-Design 재평가 (Phase 1 후)**: 위반 없음 — Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-260-admin-food-edit-safety/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── admin-food-edit-pages.md   # SSR 페이지·폼 계약
└── tasks.md             # Phase 2 output (/speckit-tasks — 이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/admin/
├── AdminFoodPageController.kt   # [수정] GET /admin/foods/list 에 edit 파라미터 추가 → 모델 editMode 노출
└── AdminFoodService.kt          # [수정] updateFood 저장 시 food.transitionByContentState() 호출 1줄

api/src/main/resources/templates/admin/
└── food-list.html               # [수정] 모달 입력 disabled 기본 + 편집/취소 링크 + 저장 버튼 편집 모드 한정

api/src/test/kotlin/com/kbap/api/admin/
├── AdminFoodListControllerTest.kt   # [확장] 읽기 전용 기본·편집 모드·취소 복원 렌더링 시나리오
└── AdminFoodServiceTest.kt          # [확장] 상태 자동 보정(검수 이전 재계산·검수 단계 보존) 시나리오
```

**Structure Decision**: 변경 파일 4곳(+테스트 2곳)으로 전부 `:api` 내부. `:common`·Flyway·공용 REST API 는 건드리지 않는다. 도메인 전이 규칙은 `common/.../Food.kt`의 기존 메서드를 호출만 한다.

## Complexity Tracking

위반 없음 — 해당 없음.

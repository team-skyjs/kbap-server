# Implementation Plan: 관리자 대시보드 확장 — 가입자·스캔·신규 음식·LLM 비용 주간 지표 시각화

**Branch**: `kb-264-admin-dashboard-metrics` | **Date**: 2026-07-30 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-264-admin-dashboard-metrics/spec.md`

## Summary

기존 관리자 적재 현황 페이지(`/admin/foods`)를 확장해 총 가입자 수(ACTIVE) 카드와 최근 7일 일자별 3종 그래프(스캔 횟수·신규 등록 음식·LLM 호출 비용 USD)를 추가한다. 스키마 변경 없음 — 이미 적재된 원천(member·scan_history·food.created_at·llm_call_cost)에 집계 쿼리만 추가한다. 집계는 신규 `AdminDashboardMetricsService`(api admin 패키지)가 소유하고, 그래프는 차트 라이브러리 없이 기존 admin.css 패턴의 순수 CSS 바 차트로 렌더한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Gradle toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·data-jpa·thymeleaf — 기존 관리자 페이지 스택), 신규 의존성 없음

**Storage**: MySQL(기존 테이블 재사용: `member`·`scan_history`·`food`·`llm_call_cost`) — **Flyway 마이그레이션 없음**

**Testing**: Kotest BehaviorSpec + kotest-extensions-spring, 통합 테스트는 MySQL Testcontainers(`MySqlContainerConfig`), 페이지는 MockMvc

**Target Platform**: `:api` bootJar (관리자 서버렌더 페이지 — Thymeleaf)

**Project Type**: 웹 백엔드 모듈러 모놀리스 내 관리자 화면 확장

**Performance Goals**: 관리자 저빈도 조회 — 별도 목표 없음. 집계 4쿼리(카운트 1 + 7일 group-by 3)로 페이지 1회 렌더

**Constraints**: 기존 적재 현황 지표 회귀 없음(FR-001·SC-003) · 데이터 없는 날 0 표시(FR-006) · 관리자 인증 유지(FR-007, 기존 `AdminPageAuthInterceptor` 가 `/admin/**` 커버 — 추가 작업 없음)

**Scale/Scope**: 화면 1개 확장, 신규 서비스 1개, 리포지토리 집계 쿼리 4개, 템플릿·CSS 수정

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | ✅ | 집계 서비스는 Testcontainers 통합 BehaviorSpec, 페이지는 MockMvc BehaviorSpec 를 먼저 작성(Red)한 뒤 구현한다. |
| II. Bounded Contexts | ✅ | 교차 도메인 조합(member·scan·food·metering)은 `com.kbap.api.admin` 기능 패키지가 소유 — api 기능 패키지는 도메인 방향 맵 대상이 아니다. 도메인 간 신규 의존 없음. |
| III. Layered Dependency Direction | ✅ | `:api` → `:common` 방향만 사용. 신규 모듈·seam 없음. |
| IV. Persistence Ownership | ✅ | 집계 쿼리는 소유 도메인 리포지토리(`MemberJpaRepository`·`ScanHistoryJpaRepository`·`FoodJpaRepository`·`LlmCallCostJpaRepository`)에 추가하고, 소비 계층(admin 서비스)이 직접 주입한다. 위임 전용 창구 서비스 없음 — `AdminDashboardMetricsService` 는 4원천 조합 + 0-fill 로직을 소유한다. `@Transactional(readOnly = true)` 명시. JPA 연관관계 추가 없음. |
| V. Language Policy | ✅ (해당 없음) | 음식 콘텐츠 번역과 무관한 관리자 한국어 화면. `lang` 파라미터 없음. |

관리자 서비스 분리 원칙(공용 도메인 서비스 오염 금지)도 준수 — 도메인 서비스는 건드리지 않고 리포지토리 쿼리 + admin 전용 서비스로만 구현한다.

**Post-Phase-1 재평가**: 위반 없음 — Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-264-admin-dashboard-metrics/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── admin-dashboard-page.md   # /admin/foods 페이지 모델 계약
└── tasks.md             # Phase 2 output (/speckit-tasks — 이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/
├── member/MemberJpaRepository.kt        # +countByMemberStatus(ACTIVE)
├── scan/ScanHistoryJpaRepository.kt     # +최근 7일 일자별 스캔 카운트 집계
├── food/FoodJpaRepository.kt            # +최근 7일 일자별 신규 음식 카운트 집계
└── metering/LlmCallCostJpaRepository.kt # +최근 7일 일자별 costUsd 합계 집계

api/src/main/kotlin/com/kbap/api/admin/
├── AdminDashboardMetricsService.kt      # 신규 — 4지표 조합·0-fill·뷰 모델
└── AdminFoodPageController.kt           # /admin/foods 모델에 metrics 추가

api/src/main/resources/
├── templates/admin/foods.html           # 가입자 카드 + CSS 바 차트 3개 추가
└── static/assets/admin.css              # 바 차트 스타일 추가

api/src/test/kotlin/com/kbap/api/admin/
├── AdminDashboardMetricsServiceTest.kt  # 신규 — Testcontainers 집계 검증
└── AdminFoodPageControllerTest.kt       # 기존 확장 — metrics 모델 검증
```

**Structure Decision**: 기존 `com.kbap.api.admin` 기능 패키지 확장. 신규 페이지·신규 모듈·마이그레이션 없이 기존 `/admin/foods` 대시보드 화면에 지표를 추가한다(research.md R1). 집계 쿼리는 각 소유 도메인의 리포지토리에 둔다(원칙 IV).

## Complexity Tracking

위반 없음 — 기재 사항 없다.

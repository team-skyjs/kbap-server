# Implementation Plan: 관리자 페이지 — 음식 데이터 적재 현황·회원 관리 화면

**Branch**: `kb-245-admin-page-ui` | **Date**: 2026-07-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-245-admin-page-ui/spec.md`

## Summary

:api bootJar 에 타임리프 기반 관리자 페이지를 추가한다(KB-245·246). `com.kbap.api.admin` 기능 패키지 안에 뷰 컨트롤러·관리자 서비스·로그인을 두고, 영속은 `:common` 의 기존 리포지토리(`FoodJpaRepository`·`MemberJpaRepository`)를 직접 소비한다. 신규 인프라·신규 모듈 없음 — 관리자 자격 증명은 신규 **`admin_account` 테이블**(자체 로그인, clarify 2026-07-29)에 보관하고, 로그인 성공 시 기존 `TokenIssuer` 로 ADMIN JWT 를 발급해 HttpOnly 쿠키에 담는다(무상태 — prod api 2대 환경에서 세션 스티키 불필요). 화면은 사이드바(음식 데이터·회원 관리) 레이아웃 + 적재 현황 대시보드 + 시드 등록/이미지 배치 제출 폼 + 회원 목록(페이징)/상세로 구성하고, 단일 CSS 디자인 토큰으로 768px(아이패드 미니)까지 유지되는 고정형 레이아웃을 만든다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1 (`:api` 에 `spring-boot-starter-thymeleaf` 신규 추가), `spring-security-crypto`(BCrypt 단독 — Spring Security 풀스택 미도입), 기존 `common.port.auth`(`TokenIssuer`·`TokenParser`) seam

**Storage**: 기존 MySQL — 신규 테이블 1(`admin_account`) + Flyway 마이그레이션 1건. `FoodJpaRepository` 에 상태별 집계 쿼리 1개 추가

**Testing**: Kotest `BehaviorSpec`(given/when/then 한국어) + Spring `@SpringBootTest`/MockMvc + MySQL Testcontainers(`:common` testFixtures)

**Target Platform**: `:api` bootJar 내 `/admin/**` 경로 (기존 ECS 배포 그대로)

**Project Type**: web-service 내 서버 렌더링 관리자 화면 (Thymeleaf SSR)

**Performance Goals**: 내부 도구 — 관리자 1~2명, 동시성 목표 없음. 대시보드 집계는 단일 group-by 쿼리

**Constraints**: 화면 폭 768px(아이패드 미니 세로)까지 레이아웃 유지, 그 미만 반응형 미지원. 기존 REST 관리자 API(`/api/v1/admin/**`)와 그 인증 체계는 무변경

**Scale/Scope**: 화면 5개(로그인·음식 대시보드·회원 목록·회원 상세 + 공통 레이아웃), 신규 뷰 라우트 9개, 신규 테이블 1(`admin_account` — 계정 등록·비번 변경 화면은 범위 밖, 최초 계정 수동 INSERT)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | 모든 task 를 Red→Green→Refactor 로 진행(tasks 단계에서 테스트 선행 배치). 로그인 검증·인터셉터·집계·페이징·뷰 컨트롤러 전부 실패 테스트 선작성 |
| II. Bounded Contexts | PASS | 화면 조합·관리자 서비스는 전부 `com.kbap.api.admin`(api 기능 패키지) — 도메인 패키지 신설·침범 없음. food·member 는 리포지토리 직접 소비(ID 기반), 도메인 간 의존 맵 변경 없음 |
| III. Layered Dependency Direction | PASS | 의존 방향 api→common 만 사용. Thymeleaf·security-crypto 는 `:api` 전용 의존. `common.port.auth` seam 재사용(구현 `:infra:auth` 무변경) |
| IV. Persistence Ownership | PASS | 신규 엔티티 `AdminAccount` 는 소유 도메인 패키지(`common.domain.admin`)에 BaseEntity 상속으로 두고 Flyway(스키마 owner=api)가 테이블을 만든다. 집계 쿼리는 소유 패키지(`common.domain.food.FoodJpaRepository`)에 추가. 조회 서비스는 명시적 `@Transactional(readOnly = true)`. 위임 전용 창구 서비스 안 만듦 — 화면 조합 로직이 있는 admin 서비스만 둔다 |
| V. Domain Content Language Policy | PASS(비대상) | 관리자 화면은 내부 도구로 한국어 고정 — 사용자 노출 콘텐츠·`lang` 파라미터 정책 대상 아님 |
| 추가 제약: 도메인 모델 응답 노출 금지 | PASS | 템플릿에는 엔티티가 아닌 화면 전용 뷰 모델(`Admin*View`)만 전달 |

**Post-Design Re-check (Phase 1 완료 후)**: 위반 없음 — Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-245-admin-page-ui/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── admin-pages.md   # Phase 1 output — 뷰 라우트 계약
└── tasks.md             # Phase 2 output (/speckit-tasks — 이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/admin/
├── AdminController.kt                  # 기존 REST — 무변경
├── AdminAuthorizationInterceptor.kt    # 기존 REST 인가 — 무변경
├── AdminLoginService.kt                # 신규 — admin_account 자격 증명 검증(BCrypt) + ADMIN JWT 발급
├── AdminPageAuthInterceptor.kt         # 신규 — /admin/** 쿠키 JWT 검사, 실패 시 로그인 리다이렉트
├── AdminPageController.kt              # 신규 — 로그인/로그아웃 + 홈 리다이렉트 뷰 컨트롤러
├── AdminFoodPageController.kt          # 신규 — 음식 대시보드 + 시드 등록/이미지 제출 폼 처리
├── AdminMemberPageController.kt        # 신규 — 회원 목록(페이징)·상세
├── AdminFoodDashboardService.kt        # 신규 — 상태별 집계 → 뷰 모델 조립
├── AdminMemberQueryService.kt          # 신규 — 회원 페이지 조회 → 뷰 모델 조립
└── (뷰 모델 data class 들 — *View)

api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt   # 수정 — AdminPageAuthInterceptor 등록
api/src/main/kotlin/com/kbap/api/core/auth/AuthMemberId*ArgumentResolver.kt  # 수정 — role=ADMIN 토큰의 회원 신원 해석 거절(주체 혼동 가드)
api/src/main/resources/templates/admin/                     # 신규 — layout.html, login.html, home.html(US2 에서 대체), foods.html, members.html, member-detail.html
api/src/main/resources/static/assets/admin.css              # 신규 — 디자인 토큰 단일 CSS (/admin/** 인터셉터 범위 밖)
api/build.gradle.kts                                        # 수정 — thymeleaf·security-crypto 의존 추가

common/src/main/kotlin/com/kbap/common/domain/food/FoodJpaRepository.kt  # 수정 — 상태별 집계 쿼리 추가
common/src/main/kotlin/com/kbap/common/domain/admin/                     # 신규 — model/AdminAccount.kt + AdminAccountJpaRepository.kt
api/src/main/resources/db/migration/V...__create_admin_account_table.sql # 신규 — admin_account 테이블
api/src/test/kotlin/com/kbap/api/architecture/ModuleBoundaryTest.kt      # 수정(필요 시) — admin 컨텍스트 허용 맵 등록

api/src/test/kotlin/com/kbap/api/admin/                     # 신규 테스트 (BehaviorSpec)
common/src/test/kotlin/com/kbap/common/domain/food/         # 집계 쿼리 통합 테스트
```

**Structure Decision**: 신규 Gradle 모듈 없이 기존 `com.kbap.api.admin` 기능 패키지 확장(사용자 결정 — 패키지 격리로 충분, KB-244 모듈 다이어트 원칙 유지). 파일 수가 적어 `service`·`dto` 하위 패키지를 만들지 않는다(CLAUDE.md 컨벤션).

**PR 분할(2026-07-29 Codex 리뷰 반영)**: PR-A = Phase 1~3(인증 기반+로그인+레이아웃, MVP) → develop 머지 후 PR-B = Phase 4~7(대시보드·폼·회원 화면). 상세는 tasks.md.

## Complexity Tracking

위반 없음 — 해당 없음.

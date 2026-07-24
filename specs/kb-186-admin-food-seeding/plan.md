# Implementation Plan: 신규 음식 적재 관리자 API (Admin Food Seeding)

**Branch**: `kb-186-admin-food-seeding` | **Date**: 2026-07-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-186-admin-food-seeding/spec.md`

## Summary

관리자만 호출할 수 있는 API 로 한국 음식 메뉴 이름 목록을 받아, 기존 food(korean_name)와 대조해 신규 이름만 **INCOMPLETE** 로 멱등 적재한다. 콘텐츠 채움 파이프라인(KB-182~184)의 두 번째 입구다(기존 입구는 메뉴판 스캔).

기술 접근:

- **적재 코어는 이미 존재**한다 — `FoodService.createIncomplete(names)` 가 `INSERT … ON DUPLICATE KEY UPDATE id=id`(insert-or-ignore)로 korean_name 충돌을 no-op 처리한다. 멱등성(FR-005)·동시성(FR-008)이 이 SQL 로 보장되므로 도메인 쓰기 로직을 새로 만들지 않는다. 관리자 컨트롤러가 `FoodService` 를 직접 주입해 호출한다(`ScanController → ScanService` 와 동일 패턴).
- **신규는 인가뿐**이다 — `MemberRole` 에 `ADMIN` 값을 추가하고, 서명 검증된 JWT 의 `role` 클레임이 `ADMIN` 인지 검사하는 인가 가드(인터셉터)를 `/api/v1/admin/**` 에 건다. 서명(HS256, 기존 `kbap.auth.jwt.secret`)이 위조를 막으므로 role 클레임만으로 인가가 성립한다(Clarify Q1·Q2).
- **미조사 센티널(맵기 -1·회피 성분 null)** 은 공유 미완성 음식 생성 경로(`Food.incomplete()`·`upsertIncomplete` SQL·컬럼 nullable 스키마)가 제공한다. 이 변경은 **배치 파이프라인 세션 소유**이고 kb-186 은 재사용만 한다(FR-009, 아래 의존성).

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (Web MVC · Data JPA), jjwt(기존 인증), springdoc-openapi(Swagger) — **신규 의존 없음**

**Storage**: MySQL(`food` 테이블, Flyway 스키마) — 신규 테이블·컬럼 없음(kb-186 범위 내)

**Testing**: Kotest BehaviorSpec(given/when/then 한국어), 통합은 MySQL Testcontainers

**Target Platform**: `:app:api` bootJar(리눅스 서버)

**Project Type**: web-service (모듈러 모놀리스)

**Performance Goals**: 관리자 수동 트리거 · 단발 배치 insert. 특별 목표 없음(N/A)

**Constraints**: TDD(원칙 I) · 외부 입력 검증은 요청 경계(DTO)가 소유(원칙 V) · 도메인 모델을 응답으로 직노출 금지(응답 DTO 사용)

**Scale/Scope**: 관리자 큐레이션 목록(수십~수백 건 규모) 일괄 제출

## Constitution Check

*GATE: Phase 0 전 통과, Phase 1 후 재확인.*

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First | ✅ | 도메인(`FoodService.seedIncomplete` 카운트·멱등·blank/dedup)·API(403/200·멱등·센티널) 실패 테스트 선작성 후 구현. tasks 단계에서 Red 우선 강제 |
| II. Bounded Contexts | ✅ | food·member(역할 enum)만 손댐. 도메인 간 직접 의존 없음. `ADMIN` 은 `MemberRole` 소유 모듈(`:domain:member`)에 추가. 조합은 `:app:api` 컨트롤러에서 |
| III. Layered Dependency | ✅ | `:app:api` 컨트롤러 → `:domain:food` `FoodService` 직접 호출(기존 `ScanController` 선례). 역방향 의존 없음 |
| IV. Persistence Encapsulation | ✅ | 신규 엔티티·리포지토리 없음. 기존 `FoodService`(창구)만 사용, food 엔티티·repo 는 `internal` 유지. 응답은 DTO |
| V. Language/검증 경계 | ✅ | `lang` 파라미터 없음. blank 필터·dedup 은 요청 DTO(`toKoreanNames()`)가 소유, 도메인은 확정 `Set<String>` 수신 |
| Additional(신규 의존 금지·ArchUnit) | ✅ | 신규 라이브러리 0. 모듈 경계 위반 없음 → `ModuleBoundaryTest` 유지 |

**위반 없음** → Complexity Tracking 비움.

## 의존성 (Cross-session — `kb-182-batch-pipeline-skeleton`)

- **FR-009 센티널**(맵기 -1·회피 성분 null)은 공유 생성 경로에서 나온다: `Food.incomplete()`(→ `SPICINESS_UNASSESSED=-1`·`avoidanceSubstances=null`) + `avoidance_substances` 컬럼 nullable Flyway 변경. 이 변경은 **kb-182 워크트리가 소유**하며 kb-186 은 수정하지 않는다.
- ✅ **갭 반영 확정(2026-07-21)**: `upsertIncomplete` SQL 의 `avoidance_substances` 하드코딩 `'[]'` → `NULL` 변경과, 마이그레이션의 INCOMPLETE 백필 `spiciness=0→-1` 일관성 보정이 **kb-182 지시서에 추가**됐다(1-bis·2-bis). spiciness 는 원래 `?` 바인딩이라 엔티티 -1 이 그대로 흐른다. kb-186 이 추가로 손댈 곳 없음.
- 순서: kb-182 머지 → kb-186 이 develop rebase 후 센티널 assert 활성화. 미반영 상태로 kb-186 을 먼저 진행하면 센티널 assert 만 `@Ignore`(사유 주석) 로 두고 나머지(인가·카운트·멱등·INCOMPLETE)는 그린으로 간다.

## Project Structure

### Documentation (this feature)

```text
specs/kb-186-admin-food-seeding/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 인가 가드·토큰 발급·멱등 방식 결정
├── data-model.md        # Phase 1 — MemberRole.ADMIN · SeedIncompleteResult · 적재 음식 목표 상태
├── quickstart.md        # Phase 1 — ADMIN 토큰 발급·curl·테스트 실행
├── contracts/
│   └── admin-food-seed.md   # POST /api/v1/admin/foods 계약
└── tasks.md             # /speckit-tasks 산출(이 명령이 만들지 않음)
```

### Source Code (repository root)

```text
core/
└── src/main/kotlin/com/kbap/core/error/ErrorCode.kt        # + ADMIN_FORBIDDEN(AUTH-008, 403)

domain/member/
└── src/main/kotlin/com/kbap/domain/member/model/MemberRole.kt   # + ADMIN

domain/food/
├── src/main/kotlin/com/kbap/domain/food/FoodService.kt          # + seedIncomplete(names): SeedIncompleteResult
├── src/main/kotlin/com/kbap/domain/food/dto/SeedIncompleteResult.kt  # (신규) requested/created/skipped
└── src/test/kotlin/com/kbap/domain/food/FoodServiceTest.kt      # + seedIncomplete 시나리오(Red 우선)

app/api/
├── src/main/kotlin/com/kbap/app/api/common/ApiPaths.kt          # + ADMIN = "$V1/admin"
├── src/main/kotlin/com/kbap/app/api/common/auth/
│   ├── AdminAuthorizationInterceptor.kt                         # (신규) ROLE_ATTRIBUTE==ADMIN 아니면 403
│   ├── JwtAuthenticationFilter.kt                               # 변경 없음(ROLE_ATTRIBUTE 이미 세팅)
│   └── WebMvcAuthConfig.kt                                      # admin 경로를 필터 URL 패턴 + 인터셉터 등록
└── src/main/kotlin/com/kbap/app/api/admin/
    ├── AdminFoodApi.kt              # (신규) Swagger 인터페이스
    ├── AdminFoodController.kt       # (신규) POST /api/v1/admin/foods → FoodService.seedIncomplete
    ├── AdminFoodSeedRequest.kt      # (신규) { koreanNames: List<String> } + toKoreanNames() blank필터·dedup
    └── AdminFoodSeedResponse.kt     # (신규) { requested, created, skipped }

app/api/src/test/kotlin/com/kbap/app/api/admin/
└── AdminFoodControllerTest.kt       # (신규) 403(무토큰·USER·위조서명)·200 적재·멱등·응답 카운트·INCOMPLETE
```

**Structure Decision**: 기존 모듈러 모놀리스(`:app:api` → `:domain:*` → `:core`)를 그대로 쓴다. 신규 모듈·패키지는 `app/api/.../admin` 하나뿐이고, 나머지는 기존 파일에 값(enum·ErrorCode·경로 상수)·가드 하나를 더한다.

## Complexity Tracking

> 위반 없음 — 비움.

## Phase 0 — research.md

인가 가드 방식(필터 vs 인터셉터 vs Security), ADMIN 토큰 발급 수단(로그인 없이), 멱등·카운트 산출, 센티널 의존 처리를 결정으로 확정한다.

## Phase 1 — data-model.md · contracts/ · quickstart.md

- **data-model.md**: `MemberRole { USER, ADMIN }`, `SeedIncompleteResult(requested, created, skipped)`, 적재 음식 목표 상태(INCOMPLETE·맵기 -1·회피 null — 의존).
- **contracts/admin-food-seed.md**: `POST /api/v1/admin/foods` 요청/응답/상태코드(200·400·401·403) 계약.
- **quickstart.md**: ADMIN 액세스 토큰 발급 스니펫(`JwtTokenIssuer.issueAccessToken(0, MemberRole.ADMIN)`), curl 예시, 테스트 실행법.

### Agent context update

`CLAUDE.md` 는 수정하지 않는다(고정 SPECKIT 블록 — 브랜치별 포인터 금지, 2026-07-20 결정). 플랜 발견 경로는 `.specify/feature.json`(git 비추적) → `specs/kb-186-admin-food-seeding/plan.md` 이며 feature.json 은 `/speckit-specify` 가 이미 썼다.

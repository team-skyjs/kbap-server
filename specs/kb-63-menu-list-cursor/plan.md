# Implementation Plan: 메뉴 목록 조회 API (무한 스크롤, no-offset 커서 페이지네이션)

**Branch**: `kb-63-menu-list-cursor` | **Date**: 2026-07-07 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-63-menu-list-cursor/spec.md`

## Summary

검색어 없이 `food` 전체를 **최신 등록순(id 내림차순)** 으로 페이지당 20개씩 내려주는 무한 스크롤 목록 조회 API 를 추가한다. 페이지네이션은 offset 이 아니라 **no-offset(keyset) 커서** — 요청은 선택적 `cursor`(마지막 항목 foodId), 응답은 `nextCursor`·`hasNext` 를 포함한다. 각 항목은 상세로 이어질 **숫자 foodId** + 요청 언어 표시명 + 대표 이미지 + 맵기 + **사용자 회피 기준 종합 위험도**를 담는 재사용 가능한 'food summary' 다(향후 검색 응답과 공유). 응답은 `BaseResponse` 봉투·`/api/v1` 규약을 따른다.

기술 접근: 기존 상세 조회(`GetFoodDetailUseCase`)의 언어 폴백(`LanguageResolver`)·회피 조달(`AvoidedSubstanceProvider`)·위험도 계산(`Food.overallRisk`)·카탈로그 필터를 그대로 재사용하고, 영속은 기존 두 단계 페이징 패턴(id 조회 → id-in fetch join)을 **keyset(`id < :cursor order by id desc`)** 으로 변형해 컬렉션 fetch-join + 페이지네이션의 인메모리 문제를 피한다. 페이지당 쿼리 3개(id keyset · food+substances fetch join · 카탈로그 일괄)로 N+1 없이 상수 유지.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web·data-jpa·validation·springdoc), Spring Data JPA, Kotest(BehaviorSpec) + MySQL Testcontainers

**Storage**: MySQL 8.4 (prod), 통합 테스트는 MySQL Testcontainers. `food` 테이블 기존 스키마 사용 — **신규 마이그레이션 없음**(정렬·커서 키가 PK `id` 라 추가 인덱스 불요)

**Testing**: Kotest BehaviorSpec — 도메인/유스케이스 단위(페이크 포트) + `:infra:persistence` 어댑터 슬라이스(Testcontainers) + `:app:api` MockMvc 통합

**Target Platform**: Linux 서버 (web bootJar `:app:api`)

**Project Type**: 모듈러 모놀리스 web API (ADR-0008) — 신규 코드는 `:core:food`·`:infra:persistence`·`:application:client`·`:app:api` 4계층에 분산

**Performance Goals**: 페이지 깊이 무관 상수 응답(OFFSET 스캔 제거) — keyset `WHERE id < :cursor` 는 PK 역방향 range scan. 페이지당 DB 라운드트립 3회 고정, N+1 없음

**Constraints**: 페이지 크기 20 고정 · 익명 브라우즈(로그인 불요) · 언어 폴백/거절은 원칙 V(spec 008)와 동일 · 도메인 ORM-free 유지(원칙 IV)

**Scale/Scope**: 단일 GET 엔드포인트. 신규 파일 ~8개(포트 1 메서드·JPA 쿼리 1·어댑터·유스케이스·입출력 DTO·컨트롤러·응답 DTO·Swagger 인터페이스) + 테스트

### 결정된 사항 (사용자 확인 2026-07-07)

- **정렬/커서 키**: 최신순 — `food.id` 내림차순. 커서 = 마지막 항목 foodId, 다음 페이지 = `id < cursor`.
- **상세 식별자**: 숫자 `foodId`. (상세 API 를 foodId 조회로 정합하는 작업은 **KB-98 로 분리** — 본 플랜 범위 밖. 목록 항목은 foodId 를 담고, 클릭-스루 완결은 KB-98 이 완료.)
- **항목 필드**: 리치 카드 — foodId · 요청 언어 표시명 · imageRef · 맵기 · 종합 위험도.

### 해소 불필요 (NEEDS CLARIFICATION 없음)

세 product 결정은 `/speckit-specify` 단계에서 확정, FR-013(상세 foodId 정합)은 KB-98 로 이관. 나머지 기술 미지수는 코드베이스에서 전부 해소(아래 research.md).

## Constitution Check

*GATE: Phase 0 이전 통과 필수. Phase 1 이후 재확인.*

| 원칙 | 준수 방식 | 판정 |
|------|----------|------|
| **I. Test-First (NON-NEGOTIABLE)** | 커서 경계·빈 결과·hasNext·위험도·언어폴백을 실패 테스트로 먼저 작성(도메인/유스케이스 단위 → 어댑터 슬라이스 → MockMvc 통합). Red 확인 후 Green. | ✅ |
| **II. Bounded Contexts** | food 컨텍스트만 소유. 회피 성분은 `AvoidanceSubstanceCodeRef`(코드)로만 참조, avoidance enum 직접 import 안 함. food×avoidance 조합은 `:application:client` 유스케이스에서만(상세와 동일 패턴). | ✅ |
| **III. Layered Dependency** | controller(`:app:api`) → usecase(`:application:client`) → 도메인 port(`:core:food`). application 은 port 인터페이스로만 영속 사용. | ✅ |
| **IV. Persistence Encapsulation** | keyset 쿼리는 `:infra:persistence`(`FoodJpaRepository`+어댑터)에만. `:core:food` 는 port 메서드만 추가(ORM-free). app/application 은 JPA import 안 함. ArchUnit `ModuleBoundaryTest` 무손상. | ✅ |
| **V. Domain Content Language Policy** | `LanguageResolver`(=`LanguageCode.from`) 재사용 → 미지정/번역부재 ko 폴백, 미지원 코드 400 + 지원목록. 표시명은 `content.resolveName(lang)`. | ✅ |

**위반 없음** — Complexity Tracking 비움.

## Project Structure

### Documentation (this feature)

```text
specs/kb-63-menu-list-cursor/
├── plan.md              # 본 파일
├── spec.md              # 기능 명세
├── research.md          # Phase 0 산출
├── data-model.md        # Phase 1 산출
├── quickstart.md        # Phase 1 산출
├── contracts/
│   └── menu-list-api.md  # GET /api/v1/foods 계약
└── checklists/
    └── requirements.md
```

### Source Code (repository root)

기존 모듈 구조(ADR-0008)에 신규 파일을 더한다. 상세 조회(food) 코드와 같은 패키지 트리에 배치.

```text
core/food/src/main/kotlin/com/meogo/core/food/
└── FoodRepository.kt                # (수정) 커서 목록 조회 port 메서드 추가

infra/persistence/src/main/kotlin/com/meogo/infra/persistence/food/
├── FoodJpaRepository.kt             # (수정) keyset id 조회 쿼리 추가
└── FoodRepositoryAdapter.kt         # (수정) 커서 목록 조회 구현(2단계: id keyset → id-in fetch join)

application/client/src/main/kotlin/com/meogo/application/client/food/
├── dto/BrowseMenusInput.kt          # (신규) cursor·lang
├── dto/BrowseMenusResult.kt         # (신규) items·nextCursor·hasNext + MenuSummaryView
└── usecase/BrowseMenusUseCase.kt    # (신규) 페이지 조회 + 언어폴백 + 회피 위험도 계산

app/api/src/main/kotlin/com/meogo/app/api/
├── common/Page.kt                   # (신규) 공유 커서 페이지 봉투 Page<T>(payload·hasNext·nextCursor: Long?)
└── food/
    ├── MenuListApi.kt               # (신규) Swagger @Tag 인터페이스
    ├── MenuListController.kt        # (신규) GET /api/v1/foods → BaseResponse<Page<MenuSummaryResponse>>
    └── MenuSummaryResponse.kt       # (신규) 공유 food summary(검색 재사용)

# 테스트 (미러링)
core/food/src/test/.../FoodTest 등  # 도메인 불변은 기존, 신규 로직은 usecase 단위에서
application/client/src/test/.../food/usecase/BrowseMenusUseCaseTest.kt   # 페이크 포트로 커서/hasNext/위험도/폴백
infra/persistence/src/test/.../food/FoodRepositoryAdapterTest.kt        # (보강) keyset 경계·정렬·빈결과 Testcontainers
app/api/src/test/.../food/MenuListControllerTest.kt                     # MockMvc 통합(첫페이지·다음커서·빈·잘못된커서·lang)
```

**Structure Decision**: 신규 모듈 없음. 상세 조회가 이미 4계층(core/food · infra/persistence · application/client · app/api)에 걸쳐 있으므로 동일 트리에 목록 조회를 더한다. 목록 응답 DTO(`MenuSummaryResponse`)는 FR-008 대로 향후 검색이 재사용할 공유 스키마로 `:app:api` food 패키지에 둔다.

## Complexity Tracking

> 위반 없음 — 비움.

## Phase 0 — Outline & Research

산출: [research.md](./research.md). 기술 미지수(keyset vs offset, 커서 표현, 위험도 일괄 계산, 컬렉션 fetch-join 페이지네이션, 잘못된 커서 에러 매핑, 상세 연결의 KB-98 의존)를 코드 근거로 해소.

## Phase 1 — Design & Contracts

산출:
- [data-model.md](./data-model.md) — MenuSummary(공유)·CursorPage·Cursor 엔티티, port 시그니처, keyset 규칙, 위험도 계산.
- [contracts/menu-list-api.md](./contracts/menu-list-api.md) — `GET /api/v1/foods` 요청/응답/에러 계약.
- [quickstart.md](./quickstart.md) — TDD 순서·검증 커맨드·수동 확인 절차.
- Agent context — `CLAUDE.md` 의 SPECKIT 마커를 본 플랜으로 갱신.

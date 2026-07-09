# Implementation Plan: 검색어에 맞는 메뉴 조회 API (다국어 부분 일치, no-offset 커서)

**Branch**: `kb-62-menu-search` | **Date**: 2026-07-09 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-62-menu-search/spec.md`

## Summary

검색어를 받아 **한국어 메뉴명 + 요청 언어(`lang`) 번역명** 두 이름 중 하나라도 검색어를 **부분 일치(대소문자 비구분 포함)** 하는 메뉴를, 최신 등록순(id 내림차순)으로 페이지당 20개씩 내려주는 검색 API 를 추가한다. 페이지네이션·응답 스키마·언어 폴백·회피 위험도는 **KB-63 목록 조회와 완전히 동일**하다 — no-offset(keyset) 커서(`nextCursor`·`hasNext`), 공유 `Page<MenuSummaryResponse>`·`BaseResponse` 봉투·`/api/v1` 규약, `LanguageResolver`·`AvoidedSubstanceProvider`·`overallRisk` 재사용. 검색은 **매칭 조건 하나만** 다르다.

기술 접근: KB-63 의 2단계 페이징(id keyset 조회 → id-in fetch join)을 그대로 계승하되, 1단계 id 조회에 **키워드 매칭 술어**를 얹는다. 번역명이 JSON 컬럼(`name_translations Map<langCode,String>`)에 저장되므로 요청 언어 번역명 매칭은 `JSON_EXTRACT(name_translations, '$."<lang>"')` 로 뽑아 `LIKE` 한다 — JPQL 로는 표준화가 어려워 **네이티브 쿼리**로 작성하고(그 대가로 `@SQLRestriction` 자동 ACTIVE 필터가 빠지므로 `status='ACTIVE'` 를 명시), 2단계 fetch join(`findByIdInWithAvoidanceSubstancesDesc`)은 기존 것을 그대로 재사용한다. `lang` 이 `ko`(미지정 폴백 포함)면 한국어명만 매칭, 그 외면 한국어명 OR 해당 언어 번역명. 페이지당 DB 라운드트립 2회 고정, N+1 없음.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web·data-jpa·validation·springdoc), Spring Data JPA, Kotest(BehaviorSpec) + MySQL Testcontainers

**Storage**: MySQL 8.4 (prod), 통합 테스트는 MySQL Testcontainers. `food` 테이블 기존 스키마 사용 — **신규 마이그레이션 없음**. `korean_name` 은 컬럼, 번역명은 기존 `name_translations` JSON 컬럼. 부분 일치(leading-wildcard LIKE)는 인덱스를 못 타므로 **추가 인덱스도 두지 않는다**(카탈로그 규모 기준 풀스캔 수용 — 아래 R3·SC-003).

**Testing**: Kotest BehaviorSpec — 유스케이스 단위(페이크 포트로 검색 위임·키워드 검증·hasNext) + `:infra:persistence` 어댑터 슬라이스(Testcontainers 로 실제 JSON 매칭·keyset·대소문자·언어 분리) + `:app:api` MockMvc 통합(검색·빈 검색어·결과없음·커서·lang)

**Target Platform**: Linux 서버 (web bootJar `:app:api`)

**Project Type**: 모듈러 모놀리스 web API (ADR-0008) — 신규 코드는 `:core:food`·`:infra:persistence`·`:application:client`·`:app:api` 4계층에 분산(KB-63 과 동일 트리)

**Performance Goals**: 페이지 깊이 무관 상수 응답(OFFSET 제거) — keyset `id < :cursor` + `LIMIT 21`. 키워드 매칭 자체는 leading-wildcard LIKE 라 페이지당 풀스캔이나, **페이지 깊이에 따라 저하되지 않음**(SC-003 은 깊이 불변을 요구). 페이지당 DB 라운드트립 2회 고정.

**Constraints**: 페이지 크기 20 고정 · 검색어 필수(공백 불가) · 매칭 대상=한국어명+요청 언어 번역명 · 익명 검색 · 언어 폴백/거절은 원칙 V(spec 008)와 동일 · 도메인 ORM-free 유지(원칙 IV)

**Scale/Scope**: 단일 GET 엔드포인트(`/api/v1/foods/search`). 신규 파일 ~7개(port 1 메서드·네이티브 검색 쿼리 1·어댑터 메서드·`SearchMenusInput`·키워드 resolver·`SearchMenusUseCase`·`MenuSearchController`+`MenuSearchApi`) + 신규 `FoodErrorCode` 1개 + 테스트. **응답 DTO(`Page`·`MenuSummaryResponse`)·`BrowseMenusResult`·`MenuSummaryView`·언어/커서/회피 컴포넌트는 전부 재사용**(신규 없음).

### 결정된 사항 (사용자 확인 2026-07-08~09)

- **매칭 대상**: 한국어명 + **요청 언어(`lang`) 번역명** 두 이름(원 Jira 설명대로). 전 언어 동시 매칭 아님(사용자 정정). `lang` 미지정/ko → 한국어명만.
- **매칭 방식**: 대소문자 비구분 **부분 일치(contains)**. MySQL `utf8mb4_0900_ai_ci` 기본 콜레이션이 LIKE 를 대소문자·악센트 비구분으로 처리 → `LOWER()` 불요(R2).
- **빈/공백 검색어**: 검색 미수행, **실패 응답**(검색어 없는 전체 탐색은 KB-63 목록 API 담당 — 역할 분리).
- **정렬/커서/항목 스키마**: KB-63 과 동일(최신순 id desc, 커서=마지막 foodId, `Page<MenuSummaryResponse>`).

### 해소 불필요 (NEEDS CLARIFICATION 없음)

product 결정은 `/speckit-specify` 에서 확정. 기술 미지수(JSON 매칭·네이티브 쿼리 ACTIVE 필터·콜레이션·인덱스)는 코드베이스·MySQL 근거로 아래 research.md 에서 해소.

## Constitution Check

*GATE: Phase 0 이전 통과 필수. Phase 1 이후 재확인.*

| 원칙 | 준수 방식 | 판정 |
|------|----------|------|
| **I. Test-First (NON-NEGOTIABLE)** | 부분 일치·빈 검색어·결과없음·커서 경계·언어별 매칭 분리를 실패 테스트로 먼저 작성(유스케이스 단위 → 어댑터 Testcontainers 슬라이스 → MockMvc 통합). Red 확인 후 Green. | ✅ |
| **II. Bounded Contexts** | food 컨텍스트만 소유. 회피 성분은 `AvoidanceSubstanceCodeRef` 코드로만 참조(avoidance enum import 안 함), food×avoidance 조합은 `:application:client` 유스케이스에서만(browse 와 동일). | ✅ |
| **III. Layered Dependency** | controller(`:app:api`) → usecase(`:application:client`) → 도메인 port(`:core:food`). application 은 port 인터페이스로만 영속 사용. port 는 kernel 타입(`LanguageCode`)만 받아 도메인-안전. | ✅ |
| **IV. Persistence Encapsulation** | 네이티브 검색 쿼리·JSON 추출은 `:infra:persistence`(`FoodJpaRepository`+어댑터)에만. `:core:food` 는 port 메서드만 추가(ORM-free, JPA/SQL 미노출). app/application 은 JPA import 안 함. ArchUnit `ModuleBoundaryTest` 무손상. | ✅ |
| **V. Domain Content Language Policy** | `LanguageResolver`(=`LanguageCode.from`) 재사용 → 미지정/번역부재 ko 폴백, 미지원 코드 400 + 지원목록. `lang` 이 매칭 대상 번역명 선택과 표시명 지역화 양쪽에 쓰임. | ✅ |

**위반 없음** — Complexity Tracking 비움.

## Project Structure

### Documentation (this feature)

```text
specs/kb-62-menu-search/
├── plan.md               # 본 파일
├── spec.md               # 기능 명세
├── research.md           # Phase 0 산출
├── data-model.md         # Phase 1 산출
├── quickstart.md         # Phase 1 산출
├── contracts/
│   └── menu-search-api.md # GET /api/v1/foods/search 계약
└── checklists/
    └── requirements.md
```

### Source Code (repository root)

KB-63 이 깐 4계층 food 트리에 검색 슬라이스를 더한다. 표시 DTO·봉투·언어/커서/회피 컴포넌트는 재사용.

```text
core/food/src/main/kotlin/com/meogo/core/food/
├── FoodRepository.kt            # (수정) searchMenuPage(keyword, lang, cursor, size) port 메서드 추가
└── FoodErrorCode.kt             # (수정) BLANK_SEARCH_KEYWORD(400) 추가

infra/persistence/src/main/kotlin/com/meogo/infra/persistence/food/
├── FoodJpaRepository.kt         # (수정) 네이티브 검색 id 쿼리 추가(korean_name OR JSON_EXTRACT(lang) LIKE, keyset, status='ACTIVE')
└── FoodRepositoryAdapter.kt     # (수정) searchMenuPage 구현(jsonPath 조립 → id 검색 → 기존 findByIdInWithAvoidanceSubstancesDesc 재사용)

application/client/src/main/kotlin/com/meogo/application/client/food/
├── dto/SearchMenusInput.kt      # (신규) keyword·cursor·lang
├── usecase/SearchKeywordResolver.kt  # (신규) trim + blank → BLANK_SEARCH_KEYWORD (resolveCursor 형제)
├── usecase/MenuSummaryAssembler.kt   # (신규·선택) browse/search 공유 위험도·뷰 조립(중복 제거) — 아래 참고
└── usecase/SearchMenusUseCase.kt     # (신규) 키워드 검증 + searchMenuPage 위임 + (조립 재사용)

app/api/src/main/kotlin/com/meogo/app/api/food/
├── MenuSearchApi.kt             # (신규) Swagger @Tag 인터페이스
└── MenuSearchController.kt      # (신규) GET /api/v1/foods/search → BaseResponse<Page<MenuSummaryResponse>>
# 재사용(신규 없음): common/Page.kt, food/MenuSummaryResponse.kt, dto/BrowseMenusResult.kt(+MenuSummaryView), LanguageResolver, AvoidedSubstanceProvider

# 테스트 (미러링)
application/client/src/test/.../food/usecase/SearchMenusUseCaseTest.kt   # 페이크 포트로 키워드 검증/hasNext/커서/빈검색어
infra/persistence/src/test/.../food/FoodRepositoryAdapterTest.kt         # (보강) 한국어/번역명 매칭·언어분리·대소문자·keyset·빈결과 Testcontainers
app/api/src/test/.../food/MenuSearchControllerTest.kt                    # MockMvc 통합(부분일치·빈검색어400·결과없음200·다음커서·lang)
```

**Structure Decision**: 신규 모듈 없음. 검색은 목록과 같은 4계층에 붙는 형제 기능이라 동일 트리에 배치한다. 항목 응답(`MenuSummaryResponse`)·페이지 봉투(`Page`)는 FR-009 대로 목록이 만든 공유 스키마를 그대로 재사용한다(신규 응답 DTO 금지). 경로는 목록(`GET /api/v1/foods`)과 충돌하지 않도록 하위 경로 **`/api/v1/foods/search`** 로 둔다.

> **MenuSummaryAssembler(선택 리팩터)**: browse·search 유스케이스의 "회피 조달 + 카탈로그 필터 + food별 overallRisk + MenuSummaryView 매핑"(~25줄, 안전 직결 위험도 로직)이 동일하다. 순수 중복이라 `MenuSummaryAssembler` 컴포넌트 1개로 추출해 두 유스케이스가 공유하는 것을 권장한다(투기적 추상화 아님 — 실재 중복 제거). 추출 시 `BrowseMenusUseCase` 도 이를 쓰도록 리팩터(테스트 녹색 유지). 부담되면 우선 인라인 복제로 Green 후 Refactor 단계에서 추출해도 된다.

## Complexity Tracking

> 위반 없음 — 비움.

## Phase 0 — Outline & Research

산출: [research.md](./research.md). 기술 미지수(JSON 번역명 매칭 방법·네이티브 쿼리의 ACTIVE 필터·콜레이션 대소문자·인덱스/성능·언어 분리·빈검색어 에러 매핑·유스케이스 중복)를 코드·MySQL 근거로 해소.

## Phase 1 — Design & Contracts

산출:
- [data-model.md](./data-model.md) — port 시그니처(`searchMenuPage`)·네이티브 검색 쿼리·어댑터 2단계·유스케이스 흐름·재사용 DTO 매핑.
- [contracts/menu-search-api.md](./contracts/menu-search-api.md) — `GET /api/v1/foods/search` 요청/응답/에러 계약.
- [quickstart.md](./quickstart.md) — TDD 순서·검증 커맨드·수동 확인 절차.
- Agent context — `CLAUDE.md` 의 SPECKIT 마커를 본 플랜으로 갱신.

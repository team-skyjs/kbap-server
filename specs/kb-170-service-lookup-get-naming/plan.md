# Implementation Plan: 서비스 조회 메서드 네이밍 get 통일

**Branch**: `kb-170-service-lookup-get-naming` | **Date**: 2026-07-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-170-service-lookup-get-naming/spec.md`

## Summary

도메인·애플리케이션 서비스의 조회 메서드 네이밍을 `get~`(없으면 `BusinessException`/non-null)으로 통일하고, null 이 도메인상 정상값인 경우에만 `get~OrNull`(반환 `T?`)을 남긴다. `find` 접두는 폐기하되 유비쿼터스 동사(`search`·`findOrSignUp`)·보조·행위는 규약 밖으로 유지한다. 페이지 조회는 이름(`get~Page`)과 반환 타입(`~Page`)을 일치시키고, List 를 반환하던 내부 로더는 `Page` 접미를 제거해 컬렉션 규칙(`get~s`, internal)으로 흡수한다. `ImageUploadService.findVerifiedImage` 는 조회가 아닌 검증 행위로 재분류해 `verifyImageAccess` 로 명명한다(반환 타입·주석·미사용 상태 유지). 외부 API 계약(요청·응답·에러 코드·HTTP 상태)은 바뀌지 않는 순수 리팩터링이다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (data-jpa), 변경 없음

**Storage**: MySQL — 스키마·마이그레이션 변경 없음 (Flyway 0건)

**Testing**: Kotest BehaviorSpec + JUnit5, MySQL Testcontainers (통합)

**Target Platform**: JVM 서버 (`:app:api` bootJar)

**Project Type**: 모듈러 모놀리스 백엔드 (Gradle 멀티모듈)

**Performance Goals**: 해당 없음 — 런타임 동작·성능 무변경

**Constraints**: 외부 API 계약 무변경(FR-006), 전체 테스트 통과(SC-001), `:app:batch`·배치 전용 조회(`nextChunk`) 범위 밖

**Scale/Scope**: 6개 도메인 서비스 + 2개 애플리케이션 서비스, 조회 메서드 리네임 12건 + 검증 재분류 1건 + 문서 1건

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | ✅ PASS | 리네임은 기존 테스트를 새 이름으로 동반 갱신해 Green 유지. 계약이 바뀌는 `findReadyById`→`getReadyFood`(null→throw)만 테스트를 먼저 `shouldThrow` 로 바꿔 Red 확인 후 구현. |
| II. Bounded Contexts | ✅ PASS | 도메인 간 새 의존 없음. 모든 리네임은 소유 모듈 내부. `verifyImageAccess` 는 `:domain:image` 안에 유지. |
| III. Layered Dependency | ✅ PASS | 의존 방향·`api`/`implementation` 표기 무변경. `getMember` public 승격은 도메인 서비스 창구 원칙과 일치(엔티티·리포지토리 노출 없음). |
| IV. Persistence Encapsulation | ✅ PASS | 리포지토리는 `internal` 유지, 엔티티 노출 없음. `getFoods`/`getFoodsByKeyword` 는 서비스 내부 로더로 `internal` 강등. |
| V. Content Language Policy | ✅ N/A | 콘텐츠·번역·언어 폴백 무관. |

**위반 없음** — Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-170-service-lookup-get-naming/
├── plan.md              # 본 파일
├── research.md          # Phase 0 — 네이밍 계약 결정·리네임 순서·충돌 회피
├── quickstart.md        # Phase 1 — 검증 런북(동작 무변경 확인)
├── checklists/
│   └── requirements.md  # spec 품질 체크리스트(통과)
└── tasks.md             # /speckit-tasks 산출물 (본 명령이 만들지 않음)
```

data-model.md·contracts/ 는 **생성하지 않는다** — 데이터 모델·엔티티·외부 인터페이스(API 계약) 변경이 0건인 순수 내부 리네임이라 해당 산출물이 비어 있게 된다(YAGNI).

### Source Code (repository root)

```text
domain/
├── member/…/MemberService.kt          # findActive→getMemberOrNull, findActiveOrThrow(priv)→getMember(pub)
├── food/…/FoodService.kt              # browse→getFoodPage, search→searchFoodPage,
│                                      #   findFoodPage→getFoods(internal), searchFoodPage(로더)→getFoodsByKeyword(internal),
│                                      #   findReadyById→getReadyFood(throw), findRandomReady→getRandomReadyFoods,
│                                      #   findAllReadyByIds→getReadyFoodsByIds, findByKoreanMatchKeys→getFoodsByKoreanMatchKeys
├── bookmark/…/BookmarkService.kt      # findBookmarks→getBookmarkPage, findBookmarkedFoodIds→getBookmarkedFoodIds
├── scan/…/ScanService.kt              # findRecentReadyFoodIds→getRecentReadyFoodIds
├── avoidance/…/AvoidanceCatalogService.kt  # findByCodes→getSubstancesByCodes
└── image/…/ImageUploadService.kt      # findVerifiedImage→verifyImageAccess (검증 행위, 반환·주석 유지)

application/
├── home/HomeApplicationService.kt     # findActive→getMemberOrNull 호출부 갱신
└── auth/AuthApplicationService.kt     # refresh: getMemberOrNull==null, withdraw: getMember 호출부 갱신

app/api/
└── …/food/FoodController.kt           # browse→getFoodPage, search→searchFoodPage 호출부 갱신

# 각 서비스의 src/test 미러 — BehaviorSpec 테스트의 메서드 참조·설명 동반 갱신
CLAUDE.md                              # 서비스 메서드 네이밍 규약 문구 갱신(get 통일 + get~OrNull 예외)
```

**Structure Decision**: 기존 모듈 구조를 그대로 유지한다. 새 파일·모듈·의존은 추가하지 않으며, 변경은 위 서비스 파일과 그 호출부(애플리케이션·컨트롤러·테스트) 및 CLAUDE.md 규약 절에 국한된다.

## Complexity Tracking

> Constitution Check 위반 없음 — 해당 없음.

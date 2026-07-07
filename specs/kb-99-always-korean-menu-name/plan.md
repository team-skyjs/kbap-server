# Implementation Plan: 언어 무관 메뉴명 한국어 항상 포함

**Branch**: `kb-99-always-korean-menu-name` | **Date**: 2026-07-08 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-99-always-korean-menu-name/spec.md`

## Summary

상세·목록 응답에 지역화 메뉴명과 **별개 필드로 한국어 원문 메뉴명(`koreanName`)** 을 추가한다. 지역화명이 이미 한국어인 경우(`lang=ko` 또는 번역 부재 폴백)에는 `koreanName=null` 로 내려 중복을 피한다. 기존 지역화·폴백 동작은 불변, 필드 추가만 하는 하위 호환 변경이다.

**기술적 접근**: 도메인 `Food` 는 이미 `content.name: LocalizedText` 에 한국어 원문(`korean`)과 지역화 해석(`resolve(lang)`)을 모두 보유한다. 따라서 **DB·영속·마이그레이션 변경 없이** 도메인에 `koreanName()` seam 을 추가하고, 두 유스케이스가 `koreanName = 한국어원문.takeIf { it != 지역화명 }` 을 계산해 Result DTO 에 담고, 두 web 응답 DTO 가 필드를 노출하도록 미러링한다.

**범위 검토 결과(사용자 요청)**: 지역화 메뉴명을 반환하는 API 는 상세·목록 둘뿐. 스캔 API(`MenuScanController`)는 메뉴명을 지역화·반환하지 않고 클라이언트 `rawMenuName` 을 받으므로 **범위 밖**. 추가 수정 API 없음.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web/data-jpa), Kotest BehaviorSpec, springdoc-openapi

**Storage**: MySQL 8.4 (통합 테스트는 MySQL Testcontainers). **이번 변경에서 스키마·Flyway 변경 없음** — 한국어 원문은 기존 `foods.korean_name`(NOT NULL) 에서 이미 로드됨.

**Testing**: JUnit5 platform + Kotest BehaviorSpec; web 통합은 `@SpringBootTest` + MockMvc + MySQL Testcontainers.

**Project Type**: 모듈러 모놀리스 web 백엔드 (`:app:api` bootJar).

**Target Platform**: Linux server (web API).

**Performance Goals**: 추가 DB 조회·연산 없음(파생 필드 계산만) — 기존 상세/목록 성능과 동일.

**Constraints**: 하위 호환(필드 추가), 요청 파라미터(`lang`·커서) 불변, 기존 지역화·폴백 응답 값 회귀 없음.

**Scale/Scope**: 3개 모듈(core:food·application:client·app:api), 파일 소규모 수정. 신규 엔티티·엔드포인트·의존성 없음.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 상태 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | ✅ PASS | 각 계층(도메인 단위·유스케이스·web 통합) 실패 테스트 선작성 후 구현. 언어별(en/ja/ko·폴백) 통합 검증(SC-003)이 DoD. |
| II. Bounded Contexts | ✅ PASS | `food` 컨텍스트 내부만 변경. 타 도메인 결합·enum import 추가 없음. |
| III. Layered Dependency Direction | ✅ PASS | 의존 방향 불변: app:api → application:client → core:food → kernel. 파생 필드가 상위로만 흐름. |
| IV. Persistence Encapsulation | ✅ PASS | 영속/JPA 변경 없음. 한국어 원문은 기존 `LocalizedText.korean` 에서 이미 복원됨. |
| V. Domain Content Language Policy | ✅ PASS | `ko` 원문 + 폴백 규칙 그대로 사용. 이 기능은 원문(ko)을 지역화명과 **함께** 노출해 정책을 강화. 미지원 코드 fail-fast(400)·요청 파라미터 불변. |

**게이트 결과: 통과. 위반 없음 → Complexity Tracking 불필요.**

## Project Structure

### Documentation (this feature)

```text
specs/kb-99-always-korean-menu-name/
├── plan.md              # 이 파일
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/           # Phase 1 (응답 스키마 델타)
├── checklists/
│   └── requirements.md  # /speckit-specify 산출
└── tasks.md             # /speckit-tasks 산출 (이 명령 아님)
```

### Source Code (repository root)

이번 변경이 건드리는 실제 경로:

```text
core/food/
├── src/main/kotlin/com/meogo/core/food/
│   ├── Food.kt                    # + koreanName(): String seam
│   └── FoodContent.kt             # + koreanName(): String (content.name.korean 위임)
└── src/test/kotlin/com/meogo/core/food/
    ├── FoodTest.kt                # koreanName 반환 검증 보강
    └── FoodContentTest.kt

application/client/
├── src/main/kotlin/com/meogo/application/client/food/
│   ├── dto/GetFoodDetailResult.kt        # + koreanName: String?
│   ├── dto/BrowseMenusResult.kt          # MenuSummaryView + koreanName: String?
│   ├── usecase/GetFoodDetailUseCase.kt   # koreanName = ko원문.takeIf { it != 지역화명 }
│   └── usecase/BrowseMenusUseCase.kt     # 동일 규약
└── src/test/kotlin/com/meogo/application/client/food/usecase/
    ├── GetFoodDetailUseCaseTest.kt       # null-if-equal / 외국어 포함 검증
    └── BrowseMenusUseCaseTest.kt

app/api/
├── src/main/kotlin/com/meogo/app/api/food/
│   ├── FoodDetailResponse.kt      # + koreanName: String? (+ @Schema)
│   └── MenuSummaryResponse.kt     # + koreanName: String? (+ @Schema)
└── src/test/kotlin/com/meogo/app/api/food/
    ├── FoodDetailLangTest.kt      # 언어별 koreanName 포함/ null 검증 보강
    └── MenuListControllerTest.kt  # 목록 각 항목 koreanName 검증 보강
```

**Structure Decision**: 기존 모듈러 모놀리스 3계층(core:food → application:client → app:api)에 파생 필드를 추가하는 수직 슬라이스. 신규 모듈·패키지 없음. 각 계층의 기존 테스트 파일을 보강한다.

## Complexity Tracking

> Constitution Check 위반 없음 — 작성 불필요.

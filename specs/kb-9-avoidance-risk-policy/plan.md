# Implementation Plan: 기피성분 포함 확률 기반 위험도 정책 + 음식 종합 위험도 판정 (KB-9)

**Branch**: `kb-9-avoidance-risk-policy` | **Date**: 2026-07-05 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-9-avoidance-risk-policy/spec.md`

## Summary

음식 상세 조회의 위험도 판단을 목(mock)에서 **실제 정책**으로 대체한다. 두 축이다:

1. **성분별 위험도(사용자 무관)**: 각 기피성분의 포함 확률 `p`로 위험도를 산출한다 — `p<10`→SAFE, `10≤p<60`→CAUTION, `p≥60`→DANGER. 이 임계값·심각도 순서는 `:core:kernel`의 `RiskLevel`이 단일 출처로 소유한다(FR-001·FR-010).
2. **음식 종합 위험도(사용자별)**: 사용자가 회피하는 성분 ∩ 음식이 포함하는 성분을 대상으로 성분별 위험도의 **최악값**을 음식 위험도로 판정한다(§4). 대상 공집합/전부 SAFE → SAFE. 판정 불가(§8 확률 결측) → UNKNOWN(절대 SAFE 아님). 이 애그리거트 정책은 `:core:food`의 `Food`가 소유한다(FR-003·FR-004·FR-007).

**핵심 제약(사용자 지시)**: 사용자 도메인(`:core:member`)·인증(JWT)이 미구현이므로 "사용자 회피 목록"만 **목(mock) 제공자**로 조달한다. 교집합 산출·위험도 정책·종합 판정은 **모두 실제로 동작**하며, 조달원만 향후 실제 프로필/인증으로 교체할 수 있는 이음새(port + mock 구현)로 둔다(FR-008). 응답에는 최상위 `overallRiskStatus` 필드를 신설한다(FR-005).

**미등록 음식 계약(확정)**: 미등록 메뉴명은 **현행 400(NOT_FOUND) 유지**. `overallRiskStatus=UNKNOWN`은 *조회된 음식*의 위험 판정에만 적용한다(§5 를 "음식이 있으나 판정 불가"로 스코프). DB 스키마·마이그레이션·엔티티 무변경.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web/validation/data-jpa), springdoc-openapi. 신규 라이브러리 없음.

**Storage**: MySQL(prod) / H2(test, create-drop, Flyway off). **스키마 변경 없음** — 포함 확률(`inclusionProbability`)·성분 코드는 이미 저장됨. Flyway 마이그레이션 없음.

**Testing**: JUnit5 platform + Kotest `BehaviorSpec`(given/when/then 한국어). 도메인 순수 단위(kernel·food), 애플리케이션 유스케이스 단위, `kotest-extensions-spring`(MockMvc·H2 통합).

**Target Platform**: Linux server (web bootJar `:app:api`)

**Project Type**: web-service (Gradle 멀티모듈 모듈러 모놀리스, ADR-0008)

**Performance Goals**: 위험도 산출은 이미 로드된 음식 애그리거트 위 순수 계산 — **추가 조회 0회**. 목 회피 목록 조달도 인메모리 상수.

**Constraints**: 도메인 ORM-free·완전 Spring-free 유지; `:core:food`는 `:core:avoidance` enum 을 import 하지 않고 코드(String)로만 참조(원칙 II); 기존 상세조회 언어 폴백/400 계약 불변; `overallRiskStatus`는 응답 **추가** 필드(기존 필드 불변).

**Scale/Scope**: `:core:kernel`(RiskLevel 정책), `:core:food`(Food 종합 판정), `:application:client`(유스케이스·목 회피 제공자·DTO), `:app:api`(응답 DTO·Swagger) 한정. 기피성분 카탈로그·메뉴 스캔·배치 불변. 목 위험도 컴포넌트(`MockAvoidanceRiskMarker`) 제거.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 준수 방법 | 판정 |
|------|-----------|------|
| **I. Test-First** | kernel 위험도 정책(임계값 경계·집계·UNKNOWN 우선)·food 종합 판정(교집합·최악값·공집합)·유스케이스(성분별 실제값·목 회피 기반 종합)·컨트롤러(overallRiskStatus·성분별 실제값·미등록 400) 각 층에 실패 테스트 선작성 → Green → Refactor. tasks 에서 층별 테스트를 구현 앞에 배치. | ✅ |
| **II. Bounded Contexts** | `:core:food`는 `:core:avoidance` enum 미import — 종합 판정은 회피 코드를 **String 집합**으로 받는다(`Food.overallRisk(avoidedCodes: Set<String>)`). 컨텍스트 조합(회피 목록 조달·enum↔코드 변환)은 `:application:client`에서만. `RiskLevel`은 kernel 공용 vocabulary. | ✅ |
| **III. Layered Dependency** | 의존 방향 불변(app→application→core→kernel). 목 회피 제공자는 **port 인터페이스**(`AvoidedSubstanceProvider`)로 두고 유스케이스가 인터페이스로만 의존, 목 구현은 교체 가능한 이음새. 신규 하위→상위 의존 없음. | ✅ |
| **IV. Persistence Encapsulation** | JPA/영속 **무변경**(스키마·엔티티·마이그레이션 없음). 위험도는 로드된 도메인 위 순수 계산이라 persistence 무관. | ✅ |
| **V. Language Policy** | 위험도는 언어 무관(enum 값). 성분명·설명 언어 폴백은 현행 유지. RiskLevel 은 사용자 노출 표시명이 아니라 상태 코드라 번역 대상 아님. | ✅ |
| **추가: 도메인/영속 모델 직접 노출 금지** | 응답은 `FoodDetailResponse` DTO 로 감싸고 도메인/엔티티 직접 반환 안 함. `overallRiskStatus`는 enum 을 String 으로 직렬화. | ✅ |
| **추가: 응답/경로 규약** | `ResponseEntity<BaseResponse<T>>`·`/api/v1` 유지. `overallRiskStatus`는 `payload` 내 추가 필드. | ✅ |

**결과**: 위반 없음. Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-9-avoidance-risk-policy/
├── plan.md              # (this file)
├── research.md          # Phase 0 — 설계 결정(정책 위치·임계값 단일출처·종합 집계·UNKNOWN 도달성·목 이음새·미등록 계약)
├── data-model.md        # Phase 1 — 도메인 정책/메서드 시그니처·DTO 변경·목 회피 집합
├── quickstart.md        # Phase 1 — 검증 시나리오(경계값·종합·목 회피·미등록 400)
├── contracts/
│   └── food-detail-api.md   # GET /api/v1/foods/detail 갱신 계약(overallRiskStatus 추가·riskStatus 실제값)
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks 산출(이 명령이 만들지 않음)
```

### Source Code (repository root) — 영향 범위

```text
core/kernel/src/main/kotlin/com/meogo/core/kernel/risk/
└── RiskLevel.kt                 # 변경: 임계값(10,60) 상수 + fromInclusionProbability(p) + aggregate(levels) 단일출처

core/food/src/main/kotlin/com/meogo/core/food/
├── FoodAvoidanceSubstance.kt    # 변경: riskLevel(): RiskLevel (= fromInclusionProbability)
└── Food.kt                      # 변경: overallRisk(avoidedCodes: Set<String>): RiskLevel (교집합·최악값·공집합→SAFE)

application/client/src/main/kotlin/com/meogo/application/client/food/
├── usecase/AvoidedSubstanceProvider.kt      # 신규: 회피 성분 코드 조달 port(이음새)
├── usecase/MockAvoidedSubstanceProvider.kt  # 신규: 고정 집합 목 구현
├── usecase/MockAvoidanceRiskMarker.kt       # 삭제
├── usecase/GetFoodDetailUseCase.kt          # 변경: 성분별 실제 위험도 + 종합 위험도, 목 마커 제거
└── dto/GetFoodDetailResult.kt               # 변경: overallRiskStatus: RiskLevel 추가

app/api/src/main/kotlin/com/meogo/app/api/food/
└── FoodDetailResponse.kt        # 변경: overallRiskStatus: String 최상위 추가 + Swagger 문구(mock 제거)

# 테스트(TDD, 구현 앞 작성)
core/kernel/src/test/.../risk/RiskLevelTest.kt                 # 신규
core/food/src/test/.../food/FoodOverallRiskTest.kt            # 신규(+ FoodAvoidanceSubstance riskLevel)
application/client/src/test/.../food/usecase/
├── GetFoodDetailUseCaseTest.kt                                # 변경(실제 위험도·종합·목 회피)
├── MockAvoidedSubstanceProviderTest.kt                        # 신규
└── MockAvoidanceRiskMarkerTest.kt                             # 삭제
app/api/src/test/.../food/FoodDetailControllerTest.kt          # 변경(overallRiskStatus·성분 riskStatus 실제값)
```

**Structure Decision**: 기존 계층을 그대로 사용한다. 신규 모듈·인프라 없음. 위험도 정책은 두 곳에 나눠 소유한다 — **임계값·심각도(RiskLevel, kernel)** 와 **음식 단위 종합 판정(Food 애그리거트, food)**. 사용자 회피 목록 조달만 애플리케이션 계층의 port + 목 구현으로 분리해, member·인증이 준비되면 목만 실제 구현으로 교체한다.

## Complexity Tracking

> Constitution Check 위반 없음 — 작성 불필요.

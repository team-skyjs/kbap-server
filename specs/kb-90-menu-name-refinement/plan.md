# Implementation Plan: 메뉴 스캔 메뉴명 정제 · 매칭 · 위험도 판정

**Branch**: `kb-90-menu-name-refinement` | **Date**: 2026-07-08 (updated 2026-07-09) | **Spec**: [spec.md](./spec.md)

**Status**: Implemented — PR #41. 실제 Upstage(solar-pro) 호출 스모크로 전 경로 검증됨.

## Summary

스캔 항목을 **정규화 → 정제 서비스(LLM) 동기 1콜 → DB 배치 매칭 → 위험도 산출**으로 처리하고, 매칭되지 않은 표준명은 **`food` 테이블에 미완성(INCOMPLETE) 상태로 등록**한다. 미완성 음식은 완성될 때까지 일반 조회(목록·상세)에 노출되지 않고 위험도는 항상 `UNKNOWN`이다. 메뉴가 아닌 항목은 응답에서 제외한다. 스캔 내역은 저장하지 않는다.

정제 서비스가 없거나 실패하면 정규화 exact 매치 폴백으로 강등하되, 음식 여부를 판정할 수 없으므로 **미완성 음식을 새로 만들지 않고** 응답에 `degraded=true`를 실어 알린다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web/validation/data-jpa), Spring AI 2.0 (`:infra:llm` — Upstage `solar-pro`, OpenAI 호환 base-url), Flyway(+flyway-mysql)

**Storage**: MySQL 8.4. 통합 테스트는 MySQL Testcontainers(`@ServiceConnection`)

**Testing**: JUnit5 + Kotest `BehaviorSpec`(given/when/then 한국어). 도메인=순수 단위, persistence=Testcontainers, web=`@SpringBootTest`+MockMvc

**Project Type**: 모듈러 모놀리스 백엔드(ADR-0008) — web 진입점 `:app:api`

**Performance Goals**: 정제 LLM 스캔당 **1콜**(타임아웃 5s, temperature 0). 음식 매칭도 스캔당 **1쿼리**(`korean_match_key IN (:keys)` fetch join).

**Constraints**: LLM 미구성·장애 시 부팅·스캔 응답이 정상이어야 한다(폴백). 미완성 음식이 사용자 조회에 새면 안 된다.

**Scale/Scope**: 스캔당 항목 1~100개.

## Constitution Check

- **I. Test-First**: 모든 슬라이스를 실패 테스트 우선으로 구현. 도메인 단위 → persistence Testcontainers → web MockMvc. ✅
- **II. Bounded Contexts**: 정규화기·LLM port는 `:core:kernel`(공유 vocabulary). 매칭 결과 값타입은 `:core:scan`. food 완성 상태는 `:core:food`가 소유. 컨텍스트 조합은 `:application:client`에서만. ✅
- **III. Layered Dependency Direction**: LLM은 `:core:kernel` port 인터페이스, `:infra:llm` 어댑터가 구현, `:app:api`가 `runtimeOnly` 조립. application은 port로만 사용. ✅
- **IV. Persistence Encapsulation**: JPA는 `:infra:persistence`에만. 도메인은 ORM-free port(`FoodRepository`). ✅
- **V. Domain Content Language Policy**: 매칭 키는 한국어 원문(`food.korean_name`) 기준. 미완성 음식은 번역이 없으므로 serving gate로 노출을 막는다. ✅

**게이트 결과: PASS**

## 최종 설계 (구현 기준)

### 흐름

```
items[]{itemId, rawMenuName}
  │
  ├─ matchKey(raw) = NFC → [가-힣]만
  │   └─ 빈 키 → 결과에서 제외 (LLM 호출 안 함)
  │
  ├─ 비지 않은 전 항목 → ScannedNameInterpreter.interpret(texts)   ← 동기 1콜
  │   응답: 같은 길이 문자열 배열, 비음식은 "NOT_FOOD" 센티넬
  │   실패·개수불일치·미구성 → degraded=true, 폴백으로 전환
  │
  ├─ 정상: StandardName → matchKey → findByKoreanMatchKeys(keys)   ← 1쿼리
  │   폴백: 원문 matchKey → 같은 조회 (단, 미완성 음식 생성 안 함)
  │
  ├─ hit + READY      → Matched(foodId), 위험도 산출
  ├─ hit + INCOMPLETE → Unmatched(foodId), UNKNOWN
  ├─ miss + LLM 확인   → createIncomplete(표준명) → Unmatched(foodId), UNKNOWN
  ├─ miss + 폴백       → Unmatched(null), UNKNOWN   (food 생성 안 함)
  └─ NOT_FOOD         → 결과에서 제외
  │
  └─ FoodRiskEvaluator.risksOf(foods)  → { foodId → RiskLevel }
```

### 핵심 결정 (근거는 [research.md](./research.md))

1. **응답 배열은 입력과 같은 길이 + `NOT_FOOD` 센티넬** — 결과를 `itemId`로 되짚으려면 위치 정렬이 필요하고, 길이 불일치가 오정렬의 유일한 검출 신호다. 비음식 필터링은 서버가 한다.
2. **미완성 음식의 위험도 가드를 도메인에 둔다** — `RiskLevel.aggregate(빈 목록)`이 `SAFE`라서, `Food.overallRisk()`가 `!isReady()`면 무조건 `UNKNOWN`을 반환한다. 호출자가 잊을 수 없는 자리.
3. **serving gate는 목록 JPQL + 상세 어댑터에만** — 스코어링 배치가 쓰는 공유 쿼리(`findByIdIn…`)에 걸면 배치가 미완성 음식을 못 봐서 영영 채워지지 않는다.
4. **폴백은 음식을 만들지 않는다** — 판정 없이 등록하면 `원산지중국` 같은 잡음이 사용자 데이터 테이블(`food`)을 오염시킨다.
5. **배치 매칭 1쿼리** — 위험도 산출에 전체 Food 애그리거트가 필요하므로 항목별 개별 조회는 100항목 × fetch join이 된다.

## Project Structure

### Documentation (this feature)

```text
specs/kb-90-menu-name-refinement/
├── plan.md · spec.md · research.md · data-model.md · quickstart.md · tasks.md
└── contracts/{ports.md, scan-api.md}
```

### Source Code

```text
core/kernel/.../menu/KoreanMenuNameNormalizer.kt     # 순수 정규화(NFC → 한글만)
core/kernel/.../scan/ScannedNameInterpreter.kt       # LLM port + InterpretedName(StandardName|NotFood)

core/scan/.../MenuItemMatch.kt                       # Matched(foodId) | Unmatched(foodId?)
core/food/.../FoodContentStatus.kt                   # INCOMPLETE | READY
core/food/.../Food.kt                                # incomplete() 팩토리, isReady(), overallRisk() 가드
core/food/.../FoodRepository.kt                      # findByKoreanMatchKeys · createIncomplete · (serving) findById/findMenuPage

infra/llm/.../menu/UpstageScannedNameInterpreter.kt  # 단일 Upstage caller, 영문 프롬프트(개수·번호·few-shot)
infra/llm/.../menu/ScannedNameParser.kt              # 문자열 배열 파싱, NOT_FOOD 센티넬, 길이 검증
infra/llm/.../config/LlmConfiguration.kt             # @ConditionalOnProperty(upstage) 빈 + temperature

infra/persistence/.../food/FoodJpaEntity.kt          # content_status, korean_match_key(생성 컬럼)
infra/persistence/.../food/FoodJpaRepository.kt      # matchKey IN 배치 fetch join, 목록 READY 필터
infra/persistence/.../food/FoodRepositoryAdapter.kt  # 동음이의 최소 id, createIncomplete get-or-create, 상세 gate

application/client/.../food/usecase/FoodRiskEvaluator.kt    # Browse·Scan 공용 위험도 산출
application/client/.../scan/usecase/SubmitMenuScanUseCase.kt # 오케스트레이션 + 폴백

app/api/.../scan/{SubmitMenuScanRequest,SubmitMenuScanResponse,MenuScanApi}.kt
app/api/src/main/resources/db/migration/             # content_status, korean_match_key, menu_scan DROP
```

**Structure Decision**: 기존 계층을 그대로 사용. 스캔 애그리거트·영속은 전부 제거됐고 `:core:scan`엔 매칭 결과 값타입만 남는다. 경계는 `ModuleBoundaryTest`(ArchUnit)로 강제.

## Complexity Tracking

> Constitution Check 위반 없음.

## 검증

- `./gradlew build` 전체 green (ArchUnit 포함)
- 로컬 docker MySQL로 Flyway 전량 적용 검증(create→drop 순서, 생성 컬럼 collation 포함)
- **실제 Upstage solar-pro 스모크**(임시 DB·8081): 로마자 제거·오탈자 교정·비음식 제외·미완성 등록·serving gate·재스캔 dedup 전부 확인

# Implementation Plan: 메뉴 스캔 제출·판정 & 음식 상세 조회 (mock 슬라이스)

**Branch**: `001-menu-scan-mock` | **Date**: 2026-06-28 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/001-menu-scan-mock/spec.md`

> **재정합 메모(2026-06-28)**: 본 plan 은 `/speckit-clarify`(Session 2026-06-28) 및 그간의 아키텍처/네이밍 변경을 반영해 재작성됐다. 주요 변경: ① 응답 봉투 `ApiResponse`/`data` → **`BaseResponse`/`payload`**, ② 영속 어댑터를 도메인 모듈의 `infrastructure` 패키지가 아니라 **중앙 `:meogo-api:persistence` 모듈**로 이관(ADR-0006, 도메인은 순수 model+port), ③ web 모듈 `api` → **`presentation`**(ADR-0005), ④ application 입출력 `Command/Query` → **`Input/Result`**, 도메인 생성 입력 `CreateCommand` → **`CreationSpec`**, ⑤ `ScannedMenuItem.receivedOrder` **제거**(itemId 는 응답 매핑용 상관 키, 순서 무의미), ⑥ US2 미수록 메뉴 **404 → 400**, ⑦ 재료 `riskStatus` = 4단계 **`RiskLevel` 재사용**, ⑧ `inclusionPercent`(연속 0~100%)와 `0/1/2`는 **별개 개념**(0/1/2는 후속 LLM per-recipe 스코어링 입력). **US1 은 이미 구현 완료**(as-built 반영), 이 plan 의 잔여 작업은 **US2(음식 상세 조회)**.

## Summary

두 개의 web API. **(1) `POST /api/v1/menu-scans`** *(US1 — 구현 완료)* — 클라이언트가 메뉴 항목(`itemId`·`rawMenuName`·`boundingBox`) 배열을 보내면, 서버가 배열 순서 기준 mock 4단계 위험도(`SAFE/CAUTION/DANGER/UNKNOWN`)를 부여하고 스캔·항목·결과를 MySQL에 최소 저장한 뒤 `itemId`로 매칭되는 결과를 반환한다. **(2) `GET /api/v1/foods/detail?menuName=&lang=`** *(US2 — 잔여)* — 메뉴명(trim 후 `ko` 원문 exact match)으로 seed된 음식 상세(요청 `lang` 음식명·대표 이미지·재료 목록[재료명·아이콘·포함%·mock `riskStatus`])를 반환하고, `lang` 미지원/미지정은 `ko` 폴백, **미수록 메뉴는 400**, `menuName` 누락/blank면 400. 음식·재료명은 `ko` 원문 + 9개 대상 언어로 저장(seed 보유).

기술 접근: 멀티모듈 골격에 도메인 코드를 채운다. 컨트롤러·DTO·`BaseResponse<T>`·예외 핸들러는 `:meogo-api:presentation`, 유스케이스·입출력(`Input/Result`)·mock seam은 `:meogo-api:application`, **순수 도메인**(model + port 인터페이스, Spring/ORM-free)은 `:meogo-api:{scan,food}`, **JPA 엔티티·Spring Data·RepositoryAdapter 는 중앙 `:meogo-api:persistence`**(ADR-0006)에 둔다. `RiskLevel`은 컨텍스트 공유 커널이라 `:meogo-api:core`. mock 판정(`MenuItemRiskAssessor`)·mock 재료 표시(`IngredientRiskMarker`)는 application 의 교체 가능한 seam(FR-013)으로 격리해 후속에 실제 `assessment`로 교체한다. 스키마(Flyway)는 `:meogo-api:presentation`이 소유한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web, validation, data-jpa), Flyway(+flyway-mysql), springdoc-openapi. 테스트: JUnit 5 platform + Kotest(`BehaviorSpec`) + `kotest-extensions-spring` + spring-boot-starter-test

**Storage**: MySQL(prod/local) + H2(test, `create-drop`, flyway off). 이번 기능은 JPA/MySQL만 사용(MongoDB 미사용)

**Testing**: `./gradlew test` — 단위(도메인·mock seam), 영속(RepositoryAdapter, H2, `:meogo-api:persistence`), web(`@SpringBootTest` + `@AutoConfigureMockMvc`)

**Target Platform**: Linux server (web bootJar `:meogo-api:presentation`, 진입점 `com.meogo.api.MeogoApiApplication`)

**Project Type**: Web service (Gradle 멀티모듈, 단일 web 앱 + 계층/도메인/영속 모듈)

**Performance Goals**: mock 동기 처리 — 사용자 체감 즉시(수 초 이내, SC-004). 외부 호출 없음

**Constraints**: 외부 LLM/네트워크 호출 없음(mock). 단일 유스케이스 트랜잭션. 도메인/영속 모델을 API 응답으로 직접 노출 금지(DTO 매핑). 도메인 모듈은 Spring/ORM-free 유지

**Scale/Scope**: 한 스캔당 항목 ≤ 100개. seed 음식 소수(데모). 2개 엔드포인트, 2개 도메인 컨텍스트(scan, food)

### 해소된 결정 (research.md / clarify)

- 포함 비율 모델: `inclusionPercent` **연속 %(0~100)** 채택. `0/1/2`는 후속 LLM per-recipe 스코어링 입력값으로 **별개 개념**(clarify 2026-06-28).
- mock 재료 `riskStatus`: 4단계 **`RiskLevel` 재사용**, `MockIngredientRiskMarker`(첫 재료 CAUTION, 나머지 SAFE)로 부여. UI의 '안전/문의 필요' 2상태는 클라이언트 매핑.
- mock 위험도 seam: application `MenuItemRiskAssessor` 인터페이스 + `MockCyclingRiskAssessor`(index%4).
- `scanId`: DB auto-increment BIGINT PK. `MenuScan` 상태: `COMPLETED` 단일(동기 mock).
- 음식 상세 매칭 키 = `ko` 원문 음식명(trim exact) · 응답 언어 = `lang` 파라미터(미지원/미지정 → `ko` 폴백, 향후 회원 출처로 교체).
- 미수록 메뉴 = **400**(clarify 2026-06-28, 이전 404 대체).

## Constitution Check

*GATE: Phase 0 전 통과 필수. Phase 1 설계 후 재점검.*

| 원칙 | 적용 | 상태 |
|------|------|------|
| **I. Test-First (NON-NEGOTIABLE)** | 모든 task 실패 테스트 먼저(Red→Green→Refactor). 도메인·mock seam·RepositoryAdapter·web 각각 선작성. 테스트는 Kotest `BehaviorSpec`(given/when/then 한국어) | ✅ PASS (tasks 강제) |
| **II. Bounded Contexts** | `scan`·`food` 도메인 모듈 상호 무의존. 두 유스케이스는 각자 한 컨텍스트만 사용(조합 없음). 조합 필요 시 `:meogo-api:application`에서만 | ✅ PASS |
| **III. Layered Dependency Direction** | `presentation → application → 도메인`. `:meogo-api:persistence`는 도메인 port 를 구현하고 `presentation`이 `runtimeOnly`로 조립. application 은 도메인 port 인터페이스에만 의존 | ✅ PASS |
| **IV. Persistence Encapsulation** | JPA Entity·Spring Data·RepositoryAdapter 를 **중앙 `:meogo-api:persistence`**(`com.meogo.api.persistence.*`)에 두고 application/presentation 은 import 안 함. 도메인은 model+port 만 노출 | ✅ PASS (단, **헌법 IV 문구는 "도메인 모듈 내부 `infrastructure` 패키지"** — 실제는 **ADR-0006** 중앙 persistence 모듈로 변경됨. **원칙의 의도(영속 캡슐화)는 충족**, 헌법 문구 동기화는 후속 `speckit-constitution` 과제) |
| **V. Domain Content Language Policy** | FoodDetail 음식명·재료명을 `ko` 원문 + 9개 대상 언어로 저장(seed 보유), API는 `lang` 번역본 반환(미지원 → `ko` 폴백). 실제 번역 생성(배치)·회원 언어 해석은 후속 | ✅ PASS |

**Additional Constraints**: 스택/2-bootJar/외부호출-트랜잭션 분리/응답 DTO 노출 금지 — 모두 충족(외부 호출 없음). **GATE 통과.**

> ⚠️ 헌법 IV 문구↔현 아키텍처(ADR-0006) 불일치는 **의도된 진화**다(영속을 도메인별 패키지 → 중앙 모듈로 이관). 원칙 의도는 유지되므로 GATE 차단 아님. 후속에 헌법 IV 본문을 ADR-0006에 맞춰 동기화 권장.

## Project Structure

### Documentation (this feature)

```text
specs/001-menu-scan-mock/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 결정 기록
├── data-model.md        # Phase 1 — 엔티티·스키마
├── quickstart.md        # Phase 1 — 실행·검증 가이드
├── contracts/           # Phase 1 — API 계약
│   ├── menu-scan-api.md  (US1)
│   └── food-detail-api.md (US2)
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks 산출
```

### Source Code (repository root)

패키지 규약: 모든 meogo-api 하위 모듈은 `com.meogo.api.<영역>` — 도메인=`com.meogo.api.<context>`, 계층=`com.meogo.api.{application}`, 영속=`com.meogo.api.persistence.<context>`, 커널=`com.meogo.api.core`, web=`com.meogo.api.presentation`, 진입점=`com.meogo.api`.

```text
meogo-api/
├── core/src/main/kotlin/com/meogo/api/core/
│   └── risk/RiskLevel.kt                         # 공유 커널: SAFE/CAUTION/DANGER/UNKNOWN  [있음]
│
├── scan/  (순수 도메인 — Spring/ORM-free)         # US1 [있음]
│   └── src/main/kotlin/com/meogo/api/scan/
│       ├── MenuScan.kt (Aggregate Root, CreationSpec)
│       ├── ScannedMenuItem.kt (itemId·rawMenuName·boundingBox·assessment)   # receivedOrder 없음
│       ├── BoundingBox.kt · MenuItemAssessment.kt · ScanStatus.kt
│       └── MenuScanRepository.kt                  # 도메인 port(인터페이스)
│
├── food/  (순수 도메인 — Spring/ORM-free)          # US2 [신규]
│   └── src/main/kotlin/com/meogo/api/food/
│       ├── Food.kt (koreanName=매칭키·names[9]·imageRef·ingredients, nameFor(lang))
│       ├── Ingredient.kt (koreanName·names[9]·iconRef, nameFor(lang))
│       ├── LanguageCode.kt (ko + 9개, 폴백 ko)
│       ├── FoodIngredient.kt (inclusionPercent 0~100, displayOrder)
│       └── FoodRepository.kt                      # 도메인 port: findByKoreanName(name): Food?
│
├── application/src/main/kotlin/com/meogo/api/application/
│   ├── scan/ (SubmitMenuScanUseCase·SubmitMenuScanInput·SubmitMenuScanResult·MenuItemRiskAssessor·MockCyclingRiskAssessor)  # US1 [있음]
│   └── food/                                       # US2 [신규]
│       ├── GetFoodDetailUseCase.kt                # menuName+lang → 상세(요청 언어)
│       ├── GetFoodDetailInput.kt / GetFoodDetailResult.kt   # Query 아님 — Input/Result
│       ├── LanguageResolver.kt                    # lang → 지원 LangCode/ko 폴백(향후 회원 출처)
│       ├── IngredientRiskMarker.kt                # 재료 riskStatus seam(인터페이스)
│       └── MockIngredientRiskMarker.kt            # mock 부여(첫 재료 CAUTION, 나머지 SAFE)
│
├── persistence/  (중앙 영속 — ADR-0006)            # com.meogo.api.persistence.*
│   └── src/main/kotlin/com/meogo/api/persistence/
│       ├── BaseEntity.kt · EntityStatus.kt        # 공통 id/status(소프트삭제)/createdAt/updatedAt  [있음]
│       ├── scan/ (MenuScanJpaEntity·ScannedMenuItemJpaEntity·MenuScanJpaRepository·MenuScanRepositoryAdapter)  # US1 [있음]
│       └── food/                                   # US2 [신규]
│           ├── FoodJpaEntity.kt / FoodNameTranslationJpaEntity.kt
│           ├── IngredientJpaEntity.kt / IngredientNameTranslationJpaEntity.kt / FoodIngredientJpaEntity.kt
│           ├── FoodJpaRepository.kt
│           └── FoodRepositoryAdapter.kt           # 음식+재료+번역 fetch join 로드 → 도메인 매핑
│
└── presentation/  (web bootJar · 조립 · 스키마 owner)
    ├── src/main/kotlin/com/meogo/api/
    │   ├── presentation/common/ (BaseResponse.kt · ApiPaths.kt · GlobalExceptionHandler.kt)  # [있음]
    │   ├── presentation/scan/ (MenuScanApi·MenuScanController·SubmitMenuScanRequest·SubmitMenuScanResponse)  # US1 [있음]
    │   └── presentation/food/                      # US2 [신규]
    │       ├── FoodDetailApi.kt / FoodDetailController.kt   # GET /api/v1/foods/detail
    │       └── FoodDetailResponse.kt              # Result → Response 변환
    ├── src/main/resources/db/migration/           # Flyway(스키마 owner)
    │   ├── V1__create_scan_tables.sql             # [있음] (received_order 없음)
    │   ├── V2__create_food_tables.sql             # US2 [신규]
    │   └── V3__seed_food_data.sql                 # US2 [신규]
    └── src/test/kotlin/com/meogo/api/presentation/...  # web 계약 테스트(MockMvc, BehaviorSpec)
```

**Structure Decision**: 기존 멀티모듈(Web service)을 그대로 사용. 신규 모듈 없음. US2는 `:meogo-api:food`(순수 도메인) + `:meogo-api:persistence/food`(영속) + `:meogo-api:application/food`(유스케이스·seam) + `:meogo-api:presentation/food`(컨트롤러·DTO) + Flyway V2/V3 에 파일을 채운다. `infra`·`member`·`assessment`·`research`·`review`·`meogo-batch`·`meogo-common`은 손대지 않는다.

## Complexity Tracking

> 헌법 위반 없음. 아래는 **의도적 단순화/이연**(위반 아님) 기록.

| 항목 | 결정 | 사유 |
|------|------|------|
| ArchUnit 경계 강제 | 이번 미도입(후속 공통 작업) | CLAUDE.md/헌법이 "추후 ArchUnit 강제"로 이연. 이번엔 모듈 경계(implementation 의존)+가시성+리뷰로 유지 |
| 헌법 IV 문구 동기화 | 후속 `speckit-constitution` | ADR-0006(중앙 persistence)로 실제 구조는 바뀌었으나 헌법 IV 본문은 "도메인 모듈 내부" 표기 유지 중. 의도는 충족 |
| `MenuScan` 상태 머신 | `COMPLETED` 단일 | 동기 mock — pending/partial 불필요. 실제 LLM 도입 시 확장 |
| 재료 `riskStatus` 출처 | application mock marker(저장 안 함) | riskStatus 는 사용자 의존(assessment) 값이라 food 데이터에 저장 안 함. seam 뒤에 둬 후속 교체 |
| `inclusionPercent` | 연속 %(0~100) | UI가 연속값 표시. `0/1/2`는 후속 LLM 스코어링 입력값으로 별개(이번 범위 밖) |
| 미수록 메뉴 응답 | 400(Bad Request) | clarify 2026-06-28 제품 결정 — 미수록 메뉴 상세 요청을 잘못된 요청으로 취급(이전 404 대체) |
| 스캔 미수록 → UNKNOWN·research 대기열 | 다음 사이클(ADR-0003/0004) | real 동작. 현 US1 은 mock 순환 유지, US2 는 대기열 없음 |

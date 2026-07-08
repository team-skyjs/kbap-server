# Implementation Plan: 메뉴 스캔 수신 메뉴명 정제 (정규화 + 잔여 해석)

**Branch**: `kb-90-menu-name-refinement` | **Date**: 2026-07-08 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-90-menu-name-refinement/spec.md`

## Summary

스캔으로 들어온 잡음 섞인 메뉴 텍스트를 **정규화 → 전부 LLM 음식명 추출 → DB 매칭 → hit/miss → miss 대기열** 로 정제·매칭한다. (1) 각 항목을 한글만 남기는 결정적 정규화 — 빈 키(한글 0자)는 LLM 스킵하고 NOT_FOOD. (2) 비지 않은 전 항목을 Upstage LLM 1콜(스캔당 배열 입출력)로 표준 한국어 메뉴명 또는 NOT_FOOD 판정. (3) 표준명을 저장 음식과 exact 매치 — hit=MATCHED, miss=PENDING+대기열, NOT_FOOD=제외. **LLM 장애·타임아웃·미구성 시엔 정규화된 텍스트로 exact 매치 폴백** — 이름이 정확한 아는 메뉴는 계속 MATCHED, 나머지는 원문 PENDING+대기열. 스캔은 항상 성공한다.

매칭은 LLM 출력 기준으로 단일화하고 exact 매치는 폴백으로 둔다(사용자 결정 — "다 LLM 으로 넘겨 음식명 추출"). 정규화 결과의 음식 여부는 형태로 판정 불가하므로 판정자는 DB exact 매치 또는 LLM 이다.

기존 스캔은 `MockCyclingRiskAssessor`로 raw 이름에 위험도를 순환 배정할 뿐 실제 매칭이 없다. 이 작업은 그 자리에 정규화·정제·매칭·라우팅을 넣는다(위험도 산출 자체는 mock 유지 — 범위 밖).

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web/validation/data-jpa), Spring AI 2.0 (`:infra:llm`, Upstage=openai 스타터 base-url 교체), Flyway(+flyway-mysql)

**Storage**: MySQL 8.4 (prod), 통합 테스트는 MySQL Testcontainers(`@ServiceConnection`, persistence testFixtures)

**Testing**: JUnit5 platform + Kotest `BehaviorSpec`(given/when/then 한국어). 도메인=순수 단위, persistence=Testcontainers, web=`@SpringBootTest`+MockMvc

**Target Platform**: Linux 서버 (web bootJar `:app:api`)

**Project Type**: 모듈러 모놀리스 백엔드 (ADR-0008) — 이 기능은 web 진입점(`:app:api`) 유스케이스

**Performance Goals**: 정제 LLM은 스캔당 1콜(비지 않은 항목 배열), 총 타임아웃 예산 ~2s. 매칭·폴백 exact 매치는 인덱스 조회.

**Constraints**: 외부 LLM 호출을 DB 트랜잭션 안에서 길게 잡지 않는다(Additional Constraints — 저장 → 외부 호출 → 결과 저장). LLM 미구성/장애 시 web 부팅·스캔 응답이 정상이어야 한다(정규화 exact 매치 폴백으로 강등).

**Scale/Scope**: foods 사전 규모 미정 — exact 매치는 인덱스 조회라 규모 무관. 스캔당 항목 1~100개(`MenuScan.MAX_ITEMS`).

## Constitution Check

*GATE: Phase 0 전 통과, Phase 1 후 재확인.*

- **I. Test-First (NON-NEGOTIABLE)**: 각 슬라이스(정규화·정제 파싱·매치·라우팅·폴백)를 실패 테스트 우선으로 작성. 정규화기·정제 파싱은 순수 단위, 매칭 조회는 persistence Testcontainers, 엔드투엔드는 web MockMvc(fake interpreter 로 정상·장애 경로). ✅ 계획됨.
- **II. Bounded Contexts**: 정규화기는 여러 컨텍스트(scan 매칭·food match-key)가 공유하는 vocabulary → `:core:kernel`에 둔다(LanguageCode 선례). 대기열은 scan 이 소유(미등록 표준명 적재). 컨텍스트 조합은 `:application:client`에서만. food 는 코드/키로만 참조. ✅.
- **III. Layered Dependency Direction**: LLM 정제는 `:core:kernel` **port 인터페이스**로 승격하고 `:infra:llm` 어댑터가 구현, `:app:api`가 `runtimeOnly` 조립(application 은 port 로만 사용, 계층 역전 없음). ✅.
- **IV. Persistence Encapsulation**: 대기열 엔티티·food match-key 컬럼·Repository 구현은 `:infra:persistence`에. 도메인은 ORM-free port(`PendingMenuRepository`, `FoodRepository.findByKoreanMatchKey`). ✅.
- **V. Domain Content Language Policy**: 한국어 원문(`foods.korean_name`)이 매칭 키의 출처. 표준명도 한국어. 언어 폴백·번역 로직은 이 기능 범위 밖(매칭은 한국어 원문 기준). ✅.
- **Additional Constraints**: 외부 호출 트랜잭션 분리(pending→호출→저장) 준수. 도메인/영속 모델을 응답에 직접 노출하지 않음(응답 DTO 미러링). ✅.

**게이트 결과: PASS** (위반 없음 — Complexity Tracking 불요).

## 구현 순서 (스펙 우선순위와 정렬)

독립 테스트 가능한 수직 슬라이스로 나눠 P1 을 먼저 머지 가능한 MVP 로 둔다. `/speckit-tasks`가 이 순서를 task 로 전개한다.

- **P1 (MVP · 정상 경로)** — US1. `:core:kernel` `KoreanMenuNameNormalizer`(순수)·`ScannedNameInterpreter` port(배열→ StandardName|NotFood), `:infra:llm` Upstage 단일 어댑터 + 응답 파서, `:app:api` `runtimeOnly` 조립. `FoodRepository.findByKoreanMatchKey` + persistence 조회(foods 생성 컬럼 `korean_match_key`+인덱스, Flyway). `ScannedMenuItem`에 매칭 결과(MATCHED foodId/PENDING/NOT_FOOD) 상태 추가. 대기열 `PendingMenuRepository`(표준명 dedup) + persistence 테이블·Flyway. `SubmitMenuScanUseCase`가 **정규화(빈 키→NOT_FOOD)→비지 않은 전 항목 LLM 1콜→표준명 exact 매치(hit=MATCHED/miss=PENDING+enqueue)/NotFood=제외**. 응답 DTO 에 matchStatus·foodId 미러링. **이 슬라이스가 정상 경로 전체 = MVP**(정제 서비스 구성 전제).
- **P2 (폴백·견고성)** — US2. interpreter 를 nullable/Optional 주입 — LLM 미구성·호출 실패·타임아웃 시 정규화된 텍스트로 exact 매치 폴백(hit=MATCHED, miss=원문 PENDING+enqueue), 아는 메뉴 응답 유지·스캔 성공. 트랜잭션 경계(저장→외부 호출→결과 저장) 준수.

## Project Structure

### Documentation (this feature)

```text
specs/kb-90-menu-name-refinement/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 정규화 규칙·match-key·LLM port·대기열 결정
├── data-model.md        # Phase 1 — 엔티티/상태/컬럼
├── quickstart.md        # Phase 1 — 로컬 검증 흐름
├── contracts/           # Phase 1 — 도메인 port·API 응답 계약
│   ├── ports.md
│   └── scan-api.md
└── tasks.md             # /speckit-tasks 산출 (이 명령이 만들지 않음)
```

### Source Code (repository root)

```text
core/kernel/src/main/kotlin/com/meogo/core/kernel/
├── menu/KoreanMenuNameNormalizer.kt      # P1 순수 정규화(한글만 → 매칭 키, 빈 키=비음식 게이트)
└── scan/ScannedNameInterpreter.kt        # P1 LLM 정제 port + 결과 값타입(StandardName|NotFood)

core/scan/src/main/kotlin/com/meogo/core/scan/
├── ScannedMenuItem.kt                    # P1 매칭 상태 필드 추가(MATCHED foodId/PENDING/NOT_FOOD)
├── MenuItemMatch.kt                      # (신규) 매칭 결과 값타입
├── PendingMenu.kt                        # (신규) 대기열 항목·큐 상태
└── PendingMenuRepository.kt              # P1 대기열 port(enqueue 값 dedup)

core/food/src/main/kotlin/com/meogo/core/food/
└── FoodRepository.kt                     # P1 findByKoreanMatchKey(key) 추가

application/client/src/main/kotlin/com/meogo/application/client/scan/
├── usecase/SubmitMenuScanUseCase.kt      # 정규화→전부 LLM→매치→라우팅(+P2 폴백) 조율
└── dto/SubmitMenuScanResult.kt           # 매칭 상태·foodId 반영

infra/llm/src/main/kotlin/com/meogo/infra/llm/menu/
├── UpstageScannedNameInterpreter.kt      # P1 port 구현(단일 Upstage caller, @ConditionalOnProperty)
└── ScannedNameParser.kt                  # P1 LLM 배열 응답 파싱

infra/persistence/src/main/kotlin/com/meogo/infra/persistence/
├── food/FoodJpaEntity.kt                 # P1 korean_match_key 매핑(생성 컬럼)
├── food/FoodJpaRepository.kt             # P1 findByKoreanMatchKey 쿼리
├── scan/ScannedMenuItemJpaEntity.kt      # P1 매칭 상태 컬럼
└── pending/PendingMenuJpaEntity.kt+Repo  # P1 대기열 테이블·adapter

app/api/src/main/kotlin/com/meogo/app/api/scan/
└── SubmitMenuScanResponse.kt             # 매칭 상태·foodId 필드 미러링

app/api/src/main/resources/db/migration/  # P1 foods 생성컬럼+인덱스, pending_menus 테이블, scan 항목 컬럼
```

**Structure Decision**: 모듈러 모놀리스(ADR-0008)의 기존 계층을 그대로 쓴다. 신규 코드는 계층별 소유 모듈에 배치 — 공유 정규화·LLM port 는 `:core:kernel`, 매칭 상태·대기열 port 는 `:core:scan`, port 구현은 `:infra:llm`/`:infra:persistence`, 조율은 `:application:client`, 조립은 `:app:api`(runtimeOnly). 경계는 기존 `ModuleBoundaryTest`(ArchUnit)로 강제된다.

## Complexity Tracking

> Constitution Check 위반 없음 — 이 절은 비워 둔다.

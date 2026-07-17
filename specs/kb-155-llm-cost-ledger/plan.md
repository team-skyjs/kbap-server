# Implementation Plan: 메뉴 스캔 LLM 호출 비용 기록 원장

**Branch**: `kb-155-llm-cost-ledger` | **Date**: 2026-07-17 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-155-llm-cost-ledger/spec.md`

## Summary

메뉴 스캔 vision(gpt-4o-mini) 호출 비용을 로그로만 남기던 것을 append-only 원장 테이블 `llm_call_cost` 에 1호출 1행으로 기록한다. 기록은 비즈니스 로직과 분리 — `OpenAiMenuBoardVisionExtractor` 가 응답 수신 직후(파싱 전) 스프링 이벤트 `LlmCallCostIncurred`(`:core`)를 발행하고, `:app:api` 의 `@Async @EventListener` 가 `:domain:metering` 의 `LlmCallCostService.record()` 로 저장한다. 기록 경로의 어떤 실패도 스캔 응답에 전파되지 않는다. 산식은 기존 `LlmPricing` 재사용(환율 1500), 저장 정밀도는 USD DECIMAL(12,6)·KRW DECIMAL(14,2) HALF_UP. 조회/집계 API 없음(범위 밖).

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Gradle toolchain)

**Primary Dependencies**: Spring Boot 4.1(web·data-jpa·context 이벤트/`@Async`), Spring AI 2.0(기존 — 신규 의존 없음), Flyway

**Storage**: MySQL — 신규 테이블 `llm_call_cost`(Flyway 마이그레이션 1건, append-only 원장)

**Testing**: Kotest BehaviorSpec + JUnit5 플랫폼, MySQL Testcontainers(`:core` testFixtures), kotest `eventually`(비동기 단언)

**Target Platform**: `:app:api` bootJar (배치는 리스너 미탑재 — 이벤트 no-op)

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 백엔드

**Performance Goals**: 스캔 응답 시간에 기록 소요 0ms 합산(비동기, FR-005) — 기록 자체는 단건 INSERT

**Constraints**: 기록 실패의 스캔 응답 비전파(FR-004), 응답 수신=과금=기록(FR-001), 금액 로그·원장 일치(FR-003/007)

**Scale/Scope**: 스캔 1회당 1행 — 저빈도 append-only, 인덱스는 `created_at` 하나로 충분

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | ✅ | 모든 신규 코드가 실패 테스트 선행(Red 확인) — 발행측 단위·리스너 통합·서비스 영속·마이그레이션 가드. tasks 단계에서 테스트 task 를 구현 task 앞에 배치 |
| II. Bounded Contexts | ✅ | 원장은 `:domain:metering` 소유(사용량·비용 계량 컨텍스트 — 2026-07-18 분리, 최초 구현은 :domain:scan). 타 도메인 참조 없음(FK 없음, 독립 원장). 공유 vocabulary(이벤트)는 `:core` |
| III. Layered Dependency Direction | ✅ | `:infra:llm` → `:core`(이벤트 클래스) 발행, `:app:api` → `:domain:metering`(서비스) 소비. 역방향 의존 없음. 이벤트는 스프링 컨텍스트 경유라 infra→도메인 컴파일 의존 불발생 |
| IV. Persistence Encapsulation | ✅ | `LlmCallCost` 엔티티(=도메인 모델, 2026-07-14 개편 기준)·리포지토리 `internal`, public 창구는 `LlmCallCostService`(record 만 노출 = append-only 강제) |
| V. Language Policy | ✅ | 음식 콘텐츠 무관(관리용 수치 데이터) |
| 외부 호출 tx 밖 | ✅ | vision 호출은 기존대로 무트랜잭션, 기록만 별도 `@Transactional`(비동기 스레드) |

> 참고: 헌법 3.0.1 문면(도메인 모델/엔티티 분리·도메인 간 의존 금지)은 2026-07-14 아키텍처 대개편(CLAUDE.md — 엔티티=도메인 모델, 도메인 간 단방향 의존 허용) 이전 문서다. 본 플랜은 최신 규약(CLAUDE.md)을 따른다 — 기존 스펙(kb-158·163·167)과 동일한 처리.

## Project Structure

### Documentation (this feature)

```text
specs/kb-155-llm-cost-ledger/
├── spec.md
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 결정 7건(R1~R7)
├── data-model.md        # Phase 1 — llm_call_cost 테이블·엔티티
├── quickstart.md        # Phase 1 — 검증 절차
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
core/src/main/kotlin/com/kbap/core/llm/
└── LlmCallCostIncurred.kt                    # [신규] Spring-free 이벤트 데이터 클래스

infra/llm/src/main/kotlin/com/kbap/infra/llm/
├── menu/OpenAiMenuBoardVisionExtractor.kt    # [수정] 응답 직후 이벤트 발행(파싱 전), 발행 실패 격리
└── config/LlmConfiguration.kt                # [수정] vision 빈에 ApplicationEventPublisher 주입

domain/metering/src/main/kotlin/com/kbap/domain/metering/
├── model/LlmCallCost.kt                      # [신규] 엔티티(BaseEntity 상속)
├── LlmCallCostJpaRepository.kt               # [신규] internal
└── LlmCallCostService.kt                     # [신규] @Service, record() 만 노출(@Transactional)

app/api/src/main/kotlin/com/kbap/app/api/
├── scan/LlmCallCostEventListener.kt          # [신규] @Async @EventListener → record()
└── config/AsyncConfig.kt                     # [신규] @EnableAsync

app/api/src/main/resources/db/migration/
└── V2026.07.17.<HHmmss>__create_llm_call_cost.sql   # [신규] 파일 생성 시각으로 채번

테스트:
infra/llm/src/test/.../menu/OpenAiMenuBoardVisionExtractorTest.kt   # [신규] 발행 시점·반올림·격리
domain/metering/src/test/.../LlmCallCostServiceTest.kt                  # [신규] 영속·정밀도
app/api/src/test/.../scan/LlmCallCostEventListenerTest.kt           # [신규] 비동기 소비(eventually)·실패 격리
```

**Structure Decision**: 발행(:infra:llm)—이벤트(:core)—원장(:domain:metering)—소비(:app:api) — 원장 모듈은 2026-07-18 :domain:scan 에서 분리(메타성 계량 데이터라 스캔 컨텍스트와 무관, 배치 LLM 확장 대비). 근거는 research.md R1~R4.

## 설계 핵심

### 이벤트 흐름

```
ScanService.scanMenuBoardImage (무트랜잭션)
  └─ visionExtractor.extract()
       ├─ chatModel.call() ──(성공: 응답 수신 = 과금 발생)──▶
       ├─ [신규] LlmCallCostIncurred 발행  ← try/catch — 발행 실패는 warn 로그 후 계속
       ├─ logTokenUsage (기존 로그 유지 — 이벤트와 동일 스냅샷 값)
       └─ parser.parse()  ← 여기서 실패해도 이벤트는 이미 발행됨(FR-001)

@Async 스레드(applicationTaskExecutor):
  LlmCallCostEventListener.handle(event)
    └─ try { llmCallCostService.record(event) } catch { error 로그 }  ← 전파 없음(FR-004)
         └─ @Transactional INSERT llm_call_cost
```

- `LlmCallCostIncurred(modelName, inputTokens, outputTokens, costUsd: BigDecimal, costKrw: BigDecimal)` — 반올림(HALF_UP, USD 6·KRW 2자리)은 extractor 에서 이벤트 생성 시 1회. usage 누락 시 토큰 0 + warn 로그, 단가 0 이면 비용 0 + warn 로그(스펙 엣지케이스).
- 모델명: `response.metadata.model` 우선, 빈 값이면 구성값 폴백(R6).
- 호출 자체 실패(네트워크·타임아웃)는 `chatModel.call()` 이 던져 이벤트 미발행 — 미과금 미기록.
- `@TransactionalEventListener` 금지 — 발행 지점이 무트랜잭션이라 유실된다(R1).

### 마이그레이션 (스키마 owner = :app:api)

`llm_call_cost` — BaseEntity 공통(id·status·created_at·updated_at) + model_name VARCHAR(100) NOT NULL, input_tokens BIGINT NOT NULL, output_tokens BIGINT NOT NULL, cost_usd DECIMAL(12,6) NOT NULL, cost_krw DECIMAL(14,2) NOT NULL, KEY idx_llm_call_cost_created_at(created_at). FK 없음(독립 원장). 상세는 data-model.md.

### 테스트 전략 (헌법 I — 모두 구현보다 먼저 Red)

| 테스트 | 모듈 | 검증 |
|--------|------|------|
| `OpenAiMenuBoardVisionExtractorTest` | :infra:llm | 성공 응답→이벤트 1회(토큰·USD/KRW 반올림·모델명), 파싱 실패에도 발행, call 예외 시 미발행, publisher 예외에도 extract 정상 반환, usage 누락→0 |
| `LlmCallCostServiceTest` | :domain:metering | record→행 저장(Testcontainers), DECIMAL 정밀도 왕복 보존 |
| `LlmCallCostEventListenerTest` | :app:api | 컨텍스트에 이벤트 발행→`eventually` 행 존재(@Async 경로), 서비스 예외 시 리스너가 삼키고 로그만(전파 없음) |

api 통합 스캔 경로는 `FakeMenuBoardVisionExtractor` 사용이라 end-to-end(스캔→행)는 커버 불가 — 발행측 단위 + 소비측 통합으로 분해 커버(R7).

## Complexity Tracking

> 위반 없음 — 신규 모듈·신규 외부 의존 0, 기존 패턴(seam·internal 리포지토리·도메인 서비스 창구) 재사용. 신규 인프라는 `@EnableAsync` 1건이 유일하며 사용자 지시(비동기 분리)의 직접 구현이다.

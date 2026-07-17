# Research: KB-155 LLM 호출 비용 기록 원장

## R1. 기록 트리거 — 스프링 이벤트 발행 vs 도메인 서비스 직접 호출

- **Decision**: `OpenAiMenuBoardVisionExtractor` 가 `chatModel.call()` 응답 수신 직후(파싱 전) `ApplicationEventPublisher` 로 이벤트를 발행하고, 비동기 리스너가 DB 에 기록한다.
- **Rationale**:
  - 사용자 지시 — 비용 기록은 비즈니스 로직과 별개이므로 이벤트/비동기로 분리.
  - usage(토큰 수)는 Spring AI `ChatResponse.metadata` 에만 존재한다. seam(`MenuBoardVisionExtractor`)의 반환 타입을 넓혀 usage 를 밖으로 내보내면, 파싱 실패 시 usage 가 유실돼 "응답 수신=과금=기록"(FR-001)을 지킬 수 없다. 발행 지점은 usage 가 살아 있는 extractor 내부가 유일하게 정확하다.
  - 발행을 파싱 앞에 두면 파싱 실패 케이스도 자동으로 기록된다(codex 스펙 검토 반영).
- **Alternatives considered**:
  - seam 반환 확장 + `ScanService` 동기 기록 — seam 이 넓어지고, 파싱 실패 시 기록 불가, 응답 경로에 기록 시간이 합산(FR-005 위반). 기각.
  - `@TransactionalEventListener` — vision 호출은 헌법상 트랜잭션 밖이라 활성 트랜잭션이 없어 이벤트가 유실된다. 기각. 플레인 `@Async @EventListener` 채택.

## R2. 이벤트 클래스 위치

- **Decision**: `:core` 에 Spring-free 데이터 클래스 `LlmCallCostIncurred` 를 둔다(`com.kbap.core.llm`).
- **Rationale**: 발행자는 `:infra:llm`, 소비자는 상위 계층 — 양쪽이 보는 공유 vocabulary 는 `:core` 가 정위치(기존 `ScannedNameInterpreter`·`MenuBoardVisionExtractor` seam 과 동일 패턴). 스프링 이벤트는 임의 객체면 되므로 `:core` 의 Spring-free 규칙을 깨지 않는다.
- **Alternatives considered**: `:infra:llm` 에 두면 도메인/앱 계층이 infra 를 컴파일 의존해야 함(계층 역전, ArchUnit 위반). 기각.

## R3. 원장 엔티티 소유 모듈 — `:domain:scan` vs 신규 모듈

- **Decision**: `:domain:scan` 이 소유한다 — `model/LlmCallCost` 엔티티 + `internal` 리포지토리 + `LlmCallCostService`(public 창구, `record` 만 노출 = append-only).
- **Rationale**: 유일한 생산자가 메뉴 스캔 vision 호출이고(KB-155 범위), scan 은 이미 api 부트앱에만 로드되는 컨텍스트다. 신규 Gradle 모듈(`:domain:llmcost`)은 행 하나 기록하는 기능에 모듈 1개를 추가하는 과설계. 테이블 구조는 스캔 비종속(FK 없음)이라 소유 모듈 이동이 필요해지면(배치 fan-out 기록 확장 시) 마이그레이션 없이 코드만 옮기면 된다.
- **Alternatives considered**: 신규 `:domain:llmcost` — 확장(배치 LLM 비용) 시 재고. 현재는 YAGNI. `:domain:image` 합류 — 의미 무관. 기각.

## R4. 비동기 리스너 위치와 실행 기반

- **Decision**: 리스너 `LlmCallCostEventListener` 는 `:app:api`(`com.kbap.app.api.scan`)에 두고 `@Async @EventListener` 로 `LlmCallCostService.record()` 를 호출한다. `:app:api` `config/AsyncConfig` 에 `@EnableAsync` 를 새로 추가한다(현재 async 설정 전무). 실행자는 Boot 기본 `applicationTaskExecutor`.
- **Rationale**: 컨트롤러가 도메인 서비스를 직접 호출하는 kbap 규약의 이벤트 버전 — 앱 계층 glue 가 event→도메인 서비스를 잇는다. 도메인 모듈은 `@Service` 창구만 유지(리스너·async 인프라 비침투). 배치 부트앱은 scan 도메인 서비스를 로드하지 않으므로 리스너 부재 → 이벤트 발행 시 no-op(안전).
- **Alternatives considered**: 리스너를 `:domain:scan` 에 — 도메인 모듈에 async 실행 인프라 결합이 생기고 배치 조립 시 혼선. 기각. 전용 스레드풀 — 기록 1건/스캔 1회 수준 부하에 불필요(YAGNI).

## R5. 금액 정밀도·타입

- **Decision**: 엔티티는 `BigDecimal`, 컬럼은 `DECIMAL(12,6)`(USD)·`DECIMAL(14,2)`(KRW). 반올림 HALF_UP 은 이벤트 생성 시점(extractor)에 1회 수행 — 로그·원장이 같은 값을 쓴다.
- **Rationale**: 기존 로그 표기가 USD 6자리·KRW 2자리(`"%.6f"`/`"%.2f"`) — FR-007 로 스펙 고정. `LlmPricing` 산식(Double)은 그대로 재사용하고 저장 직전만 `BigDecimal` 로 고정한다.
- **Alternatives considered**: Double 컬럼 저장 — 합산 오차 누적·금액 비교 불가. 기각. `LlmPricing` 자체를 BigDecimal 화 — 배치 fan-out 등 기존 소비자까지 파급, 범위 밖. 기각.

## R6. 모델명 출처

- **Decision**: `ChatResponse.metadata.model`(실제 응답 모델, 예: `gpt-4o-mini-2024-07-18`)을 우선하고, 비어 있으면 구성 모델명(`kbap.llm.vision.model`)으로 폴백한다.
- **Rationale**: 과금은 실제 서빙된 모델 기준 — 응답 메타데이터가 가장 정확. 폴백은 usage 누락 같은 메타데이터 불완전 응답 대비.

## R7. 비동기 검증 전략(테스트)

- **Decision**: `@SpringBootTest`(api, Testcontainers MySQL)에서 이벤트를 직접 발행하고 kotest `eventually` 로 행 존재를 단언한다. 발행측(extractor)은 스텁 `ChatModel` + 기록형 publisher 로 단위 검증(발행 시점·반올림·실패 격리). 리스너 실패 격리는 예외 던지는 서비스 스텁으로 propagation 부재를 단언.
- **Rationale**: api 통합 테스트의 스캔 경로는 `FakeMenuBoardVisionExtractor` 를 쓰므로 실제 발행이 일어나지 않는다 — end-to-end(스캔→행)는 통합 테스트로 검증 불가. 발행(단위) + 소비(통합)로 나눠 전 구간을 덮는다.

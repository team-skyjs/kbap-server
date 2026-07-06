# ADR-0011: 기피성분 스코어링 도메인 로직은 `:core:research`, 조율은 `:app:batch`

- **상태**: Accepted (2026-07-06)
- **관련**: [specs/kb-53-llm-avoidance-scoring](../../specs/kb-53-llm-avoidance-scoring/plan.md) · Jira KB-53 · [ADR-0004](./0004-research-bounded-context.md)(바운디드 컨텍스트·research 파이프라인) · [ADR-0010](./0010-llm-adapter-module-named-infra-llm.md)(`:infra:llm`·배치 직접 의존) · [ADR-0008](./0008-modular-monolith-shared-domain.md)(모듈러 모놀리스·배치 직접 의존)

## Context

조사 대기열의 음식을 3개 LLM(`:infra:llm` fan-out)에 병렬로 넘겨 기피성분 포함 신뢰도를 스코어링한다(KB-53). 이 흐름에는 세 갈래의 **도메인 판단 로직**이 있다.

- **프롬프트 구성** — 음식·후보 성분(카탈로그 ko 원문)·출력 스키마·번역/설명 지시를 결정적으로 조립.
- **응답 파싱** — 단일 모델 raw JSON → 구조화 결과(누락=미포함, 범위이탈·미지코드·중복·미지음식 방어 스킵, 번역·설명 파싱, 230자 절단).
- **앙상블 종합** — 3개 모델 응답 → (음식, 성분)별 최종 `inclusionConfidence`(Consensus Ensemble 공식), 텍스트(이름 번역·설명)는 앙상블 아닌 우선순위 단일 채택.

여기에 더해 실제 **조율**(대기열 읽기 → 입력 매핑 → LLM 호출 → 3모델 취합 확인 → 종합 → 결과 산출)이 필요하다.

두 축의 배치 결정이 걸렸다.

- **도메인 로직을 어디에 둘까.** LLM 상호작용의 판단 로직(프롬프트·파싱·앙상블)은 순수 함수로 표현되며 실 네트워크 없이 단위 검증 가능해야 한다(헌법 I). `:infra:llm` 은 벤더 중립 전송 계층이라 도메인 판단이 새면 안 된다(ADR-0010).
- **컨텍스트 조합을 어디서 할까.** 원칙 II 는 "컨텍스트 간 조합은 `:application:*` 에서만" 을 규정한다. 그러나 이번 스코어링은 food·avoidance 입력 수집 + LLM IO 조합을 필요로 하고, 소비자는 배치 잡 하나뿐이며 `:application:batch` 는 아직 없다.

제약: `:core:research` 는 타 도메인(food·avoidance) 타입을 직접 참조하지 않아야 한다(원칙 II 컨텍스트 격리). 신규 스키마·엔티티·마이그레이션은 없다(영속은 KB-54). 최종값은 정수 1~100(KB-9 `RiskLevel.fromInclusionProbability` 입력 호환). 3개 모델이 모두 취합돼야 확정한다(부분 집계 금지).

## Decision

**스코어링 도메인 로직(프롬프트 구성·응답 파싱·앙상블 종합·텍스트 선정)을 `:core:research` 순수 서비스에 응집하고, 조율은 `:app:batch` 잡이 얇게 수행한다.**

- `:core:research` 는 완전 Spring/ORM-free 로 프롬프트 팩토리(`ScoringPromptFactory`)·파서(`ScoringResponseParser`)·앙상블(`ConsensusEnsembleAggregator`)·텍스트 선정(`FoodContentSelector`)과 자체 값타입(`ScoringFood`·`CandidateSubstance`·`SubstanceJudgement`·`ModelScoring`·`FoodInclusionScore`·`FoodScoringResult`)을 갖는다. **research 는 food/avoidance/`:infra:llm` 타입을 import 하지 않고 primitive 값(음식명·성분코드/라벨 문자열, foodId Long)으로만 동작**한다(원칙 II 격리). kernel `LanguageCode`·`LocalizedText` 만 공유 vocabulary 로 사용한다. JSON 파싱을 위해 `jackson-module-kotlin`(Spring BOM 관리 버전과 동일하게 핀)을 순수 라이브러리로 추가하되 모듈은 Spring-free 로 유지한다.
- **조율은 `:app:batch` 잡(`AvoidanceScoringJob`)** 이 수행한다 — food·avoidance port 로 입력을 모아 research 에 primitive 로 넘기고, `LlmFanoutClient.generate` 를 호출하며, **`successes.size == 3 && failures 없음 && 3개 모두 파싱 성공**일 때만 확정(우선순위 OPENAI→UPSTAGE→GEMINI 정렬 후 종합), 아니면 청크 미확정(음식 전부 FAILED, 실패 모델별 로깅). 잡엔 **새 도메인 규칙을 두지 않고** 얇은 조율만 둔다. 음식 공급은 `:core:food` `FoodScoringSource` port(초기 구현=active food 읽기) seam 으로, 전용 대기열 테이블은 후속으로 연기한다.
- 결과적으로 **원칙 II "조합은 `:application:*` 에서만" 의 예외**가 하나 생긴다 — 조합을 `:app:batch` 잡에서 한다. ADR-0010/KB-49 가 이미 배치→`:infra:llm` 직접 호출을 채택한 선례를 따른다.

## Alternatives Considered

- **스코어링 로직을 `:infra:llm` 에 두기.** `:infra:llm` 은 벤더 중립 전송 계층이어야 한다(ADR-0010). 프롬프트/파싱/앙상블 같은 도메인 판단이 유입되면 전송과 판단이 얽히고 벤더 타입 격리가 깨진다. 순수 판단 로직은 도메인(research)에 두는 것이 응집도·테스트 용이성(실 네트워크 불요) 모두 낫다.
- **지금 `:application:batch` 를 신설해 조합을 거기서.** 원칙 II 정석이지만 배치 유스케이스가 이 하나뿐이라 유스케이스 계층 신설은 과하다(YAGNI). ADR-0004 §6 승격 트리거(①배치 유스케이스 다수, ②독립 CD 엄격, ③api·batch 공유 application)에 아직 도달하지 않았다. 트리거 충족 시 조합을 `:application:batch` 로 승격하고 잡은 트리거만 유지한다.
- **research 가 food/avoidance 타입을 직접 참조.** 컨텍스트 결합이 생겨 원칙 II 격리가 깨지고, research 단위테스트가 타 도메인 타입 구성에 묶인다. primitive 값타입 격리로 research 를 독립 검증 가능하게 유지한다(배치가 매핑 책임).
- **전용 조사 대기열 테이블을 지금 도입.** 재조사 상태·중복제거·재시도·커서가 필요해지는 시점의 후속 과제다(research.md D6). 초기엔 `FoodScoringSource` port seam 으로 두어 어댑터 구현만 후속 교체(seam 안정성)하고, 잡은 seen(foodId) 가드로 비전진 소스에서도 종료하게 한다.

## Consequences

**+**
- 스코어링 판단 로직(프롬프트·파싱·앙상블·텍스트 선정)이 `:core:research` 순수 서비스에 응집 — 실 네트워크 없이 결정적 단위 검증(헌법 I, 골든 74 고정). `:infra:llm` 은 벤더 중립 유지.
- research 가 primitive 격리라 food/avoidance 변경과 디커플 — 컨텍스트 독립 진화.
- 조율이 배치 잡 하나에 얇게 모여 배선이 단순하다(간접층 없음, ADR-0010 선례). 모델 수·후보 성분 수는 하드코딩하지 않아 카탈로그·모델 증감을 추종한다.
- `FoodScoringSource` seam 으로 전용 대기열 도입 시 어댑터만 교체(계약 안정).

**−**
- 원칙 II "조합은 application 에서만" 의 예외가 하나 존재한다(배치 조율). 배치에 도메인 규칙이 새지 않도록 리뷰로 계속 강제해야 한다(로직은 research 에만).
- `FoodScoringSource` 는 `nextChunk(page, size)` 페이지 커서로 공급하고 잡이 page 를 전진해 **한 run 에 전체 active 큐를 소진**한다(비전진 어댑터 오작동 대비 seen-foodId 가드 병행). 실행 진입점은 `ScoringJobRunner`(`ApplicationRunner`) — `@ConditionalOnProperty(meogo.scoring.runner.enabled)` 로 게이트해 기본 off(부팅 안전), 실 실행 시 on. **run 간 이미 스코어링된 음식 스킵(재조사 상태·중복제거)** 은 영속 마커(KB-54)와 전용 대기열 테이블이 필요해 후속으로 남긴다(research.md D6).
- `:core:research` 에 jackson 의존이 추가된다(Spring-free 는 유지하나 순수 도메인에 직렬화 라이브러리가 들어옴). 배치 런타임과 버전 정합을 위해 Spring BOM 관리 버전에 핀을 맞춘다.

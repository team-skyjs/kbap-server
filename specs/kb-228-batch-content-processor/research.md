# Research: 배치 음식 콘텐츠 프로세서의 작업별 구현

**Date**: 2026-07-23 | **Plan**: [plan.md](plan.md)

Technical Context 에 NEEDS CLARIFICATION 은 없다 — 스펙 Assumptions + 기존 코드(KB-182 계약·KB-224 구현·KB-209/182 배치 골격)가 미지수를 모두 해소한다. 아래는 구현 방향이 갈릴 수 있는 지점의 결정 기록이다.

## R1. 맵기(spiciness) 계약 이동 방식

- **Decision**: `FoodDescriptionContent` 에서 `spiciness` 필드를 제거하고, `FoodAvoidanceAssessmentClient.call` 의 반환을 `List<FoodAvoidanceAssessment>` → **`FoodAvoidanceAssessmentResult(substances: List<FoodAvoidanceAssessment>, spiciness: Int)`** 로 바꾼다(`init` 에서 `spiciness in 0..10` 강제). `fun interface` 는 유지한다(단일 메서드 불변 — 기존 테스트의 람다 페이크 스타일 보존).
- **Rationale**: 맵기는 회원 맵기 선호와 매칭되는 안전 직결 값(스펙 US1) — 단일 모델 판단 금지 원칙을 기피성분과 동일하게 적용하려면 같은 fan-out 호출·같은 종합 파이프라인에 있어야 한다. 별도 맵기 클라이언트를 만들면 fan-out 호출이 2배가 되고(스펙 Assumption: "한 번의 다중 모델 호출로 함께 수행"), 반환을 Pair 로 하면 계약 검증(범위 불변식)을 둘 곳이 없다.
- **Alternatives considered**: (a) 별도 `FoodSpicinessClient` — 호출 2배·Assumption 위배로 기각. (b) `FoodAvoidanceAssessment` 목록에 스페셜 코드로 편입 — 타입 안전 붕괴로 기각. (c) 설명 계약에 잔류 + 설명도 fan-out 화 — 설명·번역은 안전 직결이 아니라 단일 모델로 충분(스펙 Assumption), 비용 3배로 기각.

## R2. 맵기 종합·무효 규칙

- **Decision**: 모델별 응답 JSON 을 `{"assessments": [...], "spiciness": N}` 로 확장한다. **spiciness 가 0..10 밖이거나 누락(파싱 기본값 -1)이면 그 모델 응답 전체를 무효 처리**한다(FR-004 — 기존 "code 후보 밖/percent 범위 밖 → 응답 전체 무효" 규칙과 동일 수위). 유효 응답 2개 미만이면 기존과 같이 예외로 판정 거부(FR-003). 종합은 **유효 응답 spiciness 평균의 반올림**(`roundToInt`) — 성분 확률 종합과 동일 방식(스펙 Assumption).
- **Rationale**: 응답 무효 판정을 항목별로 쪼개면(성분은 유효한데 맵기만 무효) "유효 응답 수" 의 의미가 이중화된다 — 한 모델의 계약 위반은 그 모델 신뢰도 문제이므로 응답 전체 무효가 단순하고 보수적이다(안전 직결 fail-closed).
- **Alternatives considered**: 항목별 부분 채택(성분만 취하고 맵기는 버림) — 합의 최소치 계산이 성분·맵기 별도로 갈라져 복잡도만 늘고, "그 모델이 계약을 어겼다"는 신호를 무시하게 되어 기각.

## R3. 작업 트리거 조건과 Food 도메인 메서드

- **Decision**: `Food` 에 다음을 추가·확장한다 —
  - `needsAvoidanceAssessment(): Boolean = avoidanceSubstances == null || spiciness == SPICINESS_UNASSESSED` (③ 작업 트리거 — 기존 `needsAvoidanceMapping()` 은 READY 판정·조회용으로 유지)
  - `assessAvoidance(substances: List<FoodAvoidanceItem>, spiciness: Int)` — 기존 시그니처를 확장해 둘을 원자적으로 설정(별도 setter 금지 — 부분 저장 방지)
  - `updateNameTranslations(translations: Map<String, String>)` · `updateDescription(description: String, translations: Map<String, String>)` — CRUD 수정 `update~` 네이밍 규약. 설명은 원문+번역을 항상 함께 교체(부분 병합 없음 — 스펙 Edge Case)
- **Rationale**: 관리자 적재(KB-186) 음식은 `avoidanceSubstances = null`·`spiciness = -1` 두 센티널을 함께 갖지만, 과거 데이터나 부분 실패로 한쪽만 미완일 수 있다 — READY 조건이 `spiciness != -1` 을 요구하므로 트리거가 둘 중 하나라도 미완이면 재판정해야 영구 INCOMPLETE 가 없다. 콘텐츠 반영을 엔티티 메서드로 두는 것은 헌법 IV(도메인 로직의 도메인 소유).
- **Alternatives considered**: `needsAvoidanceMapping()` 정의 자체를 확장 — `transitionToReadyIfComplete`·조회 계열의 기존 의미(성분 미조사)와 섞여 회귀 위험, 트리거 전용 메서드 신설로 분리.

## R4. 프로세서 재편 — 작업 순서·실패 전파

- **Decision**: `process()` 를 ① `needsNameTranslations()` → 이름 번역 ② `needsDescription() || needsDescriptionTranslations()` → 설명 생성+번역(항상 원문·번역 세트 재생성) ③ `needsAvoidanceAssessment()` → 기피성분+맵기(후보 코드 비어 있으면 수행 안 함 — 현행 유지) 순으로 재편한다. 각 작업 성공 직후 기존 `saveProgress`(REQUIRES_NEW) 커밋. 이미지 블록은 스텁·조건 그대로 유지(FR-008 — READY 차단은 기존 `transitionToReadyIfComplete` 의 `!needsImage()` 가 담당). 클라이언트 예외는 그대로 전파 → 스텝의 기존 `faultTolerant().skip(...)` + `SkipListener` 로그가 처리(FR-007·SC-004).
- **Rationale**: FR-001 의 작업 열거 순서를 코드 순서로 그대로 반영해 스펙-코드 대응을 1:1 로 유지. 실패 처리·독립 커밋은 KB-182 골격이 이미 검증한 구조라 재사용(스펙 Assumption).
- **Alternatives considered**: 작업별 try-catch 로 다음 작업 계속 진행 — "실패한 음식은 skip" 의미가 흐려지고(부분 성공을 성공으로 셀 위험) 재실행 시 실패 작업만 재시도된다는 보장은 이미 즉시 커밋이 제공하므로 기각.

## R5. LLM 미구성 시 부팅 안전(boot-safety)과 클라이언트 조립

- **Decision**: `foodNameTranslationClient`·`foodDescriptionClient` 빈의 `@ConditionalOnProperty(kbap.llm.openai.enabled)` 는 유지하고, **`FoodContentBatchConfig` 가 `ObjectProvider` 로 주입해 프로세서에 nullable 로 전달**한다. 프로세서는 해당 작업이 필요한데 클라이언트가 없으면 전용 예외(`FoodContentClientNotConfiguredException`)를 던지고, 스텝 `skipPolicy` 는 이 예외만 skip 대상에서 제외해 **잡을 FAILED 로 끝낸다**(구성 오류가 전건 skip·COMPLETED 로 위장되는 것 방지 — Codex 리뷰 반영). 음식별 LLM 예외는 기존대로 무제한 skip+로그.
- **Rationale**: 기존 boot-safety 계약(키/플래그 부재 시 batch 부팅 성공 — `LlmConfigurationBootSafetyTest`)을 지키면서, 미구성 상태의 실행은 조용히 지나가지 않고 음식별 실패 로그로 드러난다. 기피성분 클라이언트가 이미 같은 성격(모델 0개면 호출 시점 예외)으로 동작하므로 일관적이다.
- **Alternatives considered**: (a) 빈을 무조건 생성하고 내부에서 caller 부재 시 예외 — caller 가 `@Qualifier("openAiModelCaller")` 조건부 빈이라 결국 provider 간접화가 필요, 조립 복잡도만 이동. (b) 클라이언트 부재 시 작업 자체를 조용히 건너뜀 — 미구성이 로그 없이 영구 INCOMPLETE 를 만들어 기각(fail-loud).

## R6. 테스트 전략(변경 파급 포함)

- **Decision**: 모듈별 Red 를 다음 순으로 구성한다 — `:core`(`FoodContentDtoTest` 의 spiciness 케이스 이동: `FoodDescriptionContent` 검증에서 제거, 신설 `FoodAvoidanceAssessmentResult` 범위 검증으로), `:domain:food`(엔티티 메서드·트리거·READY 전이), `:infra:llm`(설명 클라이언트 spiciness 제거 / 기피성분 클라이언트 spiciness 파싱·무효·종합 — 페이크 `LlmModelCaller`·`LlmFanoutClient` 기존 패턴 재사용), `:app:batch`(프로세서 3작업 단위 — 클라이언트 람다 페이크, 잡 통합 — `BatchTestClientConfig` 에 name·description 페이크 빈 추가). 시드-동기화 류 마이그레이션 결합 없음.
- **Rationale**: 계약(코어) → 구현(인프라) → 소비(배치) 의존 방향 순으로 Red→Green 을 진행해야 컴파일 실패가 단계별로 국소화된다.
- **Alternatives considered**: 배치 통합 테스트 우선 — 계약 변경이 4개 모듈 컴파일을 동시에 깨 Red 신호가 뭉개져 기각.

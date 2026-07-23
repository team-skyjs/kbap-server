# Research: 배치 콘텐츠 4작업 LLM 호출 인터페이스 사전 선언

**Date**: 2026-07-22 | **Plan**: [plan.md](plan.md)

Technical Context 에 NEEDS CLARIFICATION 은 없었다. 아래는 설계 결정과 근거.

## R1. 계약 선언 위치 — `:core` 신규 패키지 `com.kbap.core.food`

- **Decision**: 4개 인터페이스와 DTO 를 `core/src/main/kotlin/com/kbap/core/food/` 에 둔다.
- **Rationale**: 기존 외부 client seam 이 전부 `:core` 에 있다(`core/scan/MenuBoardVisionExtractor` — 구현 `:infra:llm`, `core/storage/StorageObjectStore` — 구현 `:infra:storage`). `:infra:llm` 은 `:core` 만 의존하므로(`infra/llm/build.gradle.kts`) 계약이 `:core` 에 있어야 구현이 가능하다. 헌법 III "외부 시스템 클라이언트는 port 인터페이스(seam)로만 사용" 조항의 표준 이행 위치다.
- **Alternatives considered**:
  - `:domain:food` 에 선언 — 기각: `:infra:llm` 이 도메인 모듈을 의존하게 되어 어댑터→도메인 의존이 생긴다(기존 어댑터 어디에도 없는 방향).
  - `:app:batch` 에 선언 — 기각: 구현 모듈(`:infra:llm`)이 부트앱을 의존할 수 없다(의존 역전).

## R2. 번역 DTO 키 타입 — `Map<LanguageCode, String>` + 전수 불변

- **Decision**: 공용 DTO `TargetLanguageTexts` 가 `Map<LanguageCode, String>` 를 감싸고, init 에서 **9개 대상 언어(KO 제외 전체) 전수 + 값 non-blank** 를 강제한다. 이름 번역·설명 번역이 공용한다.
- **Rationale**: "호출 1번에 9종 일괄"(Clarify Q1)을 타입이 직접 표현한다 — 누락 언어가 있는 응답은 DTO 생성 시점에 실패해 READY 완비 판정(`needsNameTranslations`)까지 흘러가지 않는다. `LanguageCode.entries` 기반이라 언어 추가 시 자동 추종.
- **적재 경로**: `Food.nameTranslations` 는 `Map<String, String>` 이므로 적재 시 `.mapKeys { it.key.code }` 만 수행 — 무손실 표현 변환으로, FR-002 의 "변환 로직 없이"가 금지하는 가공(파싱·조립·보정)에 해당하지 않는다.
- **Alternatives considered**: `Map<String, String>` 직접 반환 — 기각: 전수 보장이 문서 규약으로 밀려나고, 오타 키(`jp`)가 컴파일·생성 시점에 잡히지 않는다.

## R3. 실패 표현 — 예외(별도 Result 타입 없음)

- **Decision**: 계약 메서드는 실패 시 예외를 던진다. 계약 전용 실패 타입·Result 래퍼를 만들지 않는다.
- **Rationale**: KB-182 골격의 실패 격리가 이미 "음식 1건 try/catch → 해당 건만 INCOMPLETE 잔류" 구조다(spec US2). 예외가 곧 "호출자에게 전달 가능한 실패 표현"(FR-003)이며, 기존 seam(`MenuBoardVisionExtractor`)도 동일하다. Result 래퍼는 골격 쪽 분기만 늘린다.
- **Alternatives considered**: sealed Result 타입 — 기각: 소비처(배치 스텝)가 어차피 catch 경계 하나로 수렴, YAGNI.

## R4. 사진 생성 계약 — 호출자 키 지정, 저장은 구현 책임

- **Decision**: `FoodImageGenerationClient.call(koreanName, storageKey): String` — 호출자(배치)가 저장 키를 지정하고, 구현이 그 위치에 저장까지 완료한 뒤 키를 반환한다.
- **Rationale**: Clarify Q3 확정. `imageRef` 는 상대 키 + 읽기 시 `ImageUrls.resolve` CDN 조합 관례(kb-154)라 URL 적재 금지. 키를 호출자가 지정하므로 재시도가 같은 키 덮어쓰기로 멱등. Lambda 가 S3 직접 저장 후 키를 돌려주는 구현(KB-184 후보)과 배치 내 업로드 구현 모두 이 계약을 만족할 수 있다 — 이미지 바이트가 계약을 건너지 않아 서버 부담 절감 목적과도 부합.
- **Alternatives considered**: 이미지 바이트 반환(업로드 배치 책임) — 기각: Lambda 직접 저장 구현 시 바이트가 불필요하게 왕복. URL 반환 — 기각: CDN 키 관례 위반, 도메인 변경 시 데이터 마이그레이션 유발.

## R5. 기피성분 계약 입력 — 후보 코드는 호출자가 주입

- **Decision**: `FoodAvoidanceAssessmentClient.call(koreanName, candidateCodes: Set<String>): List<FoodAvoidanceAssessment>` — 후보 성분 코드 집합을 배치가 `:domain:avoidance` 카탈로그에서 조회해 넘긴다. 출력 항목은 `code + inclusionPercent(0..100)`. **구현은 3개 모델 API 를 호출해 응답을 종합·최종 판단**한다(안전 직결 — 기존 `LlmFanoutClient` 3모델 fan-out 관례).
- **Rationale**: 카탈로그(81종)와 `AvoidanceSubstanceCode` enum 은 `:domain:avoidance` 소유라 `:core` 계약이 참조할 수 없다(헌법 II — 타 컨텍스트는 코드 문자열로 참조). 배치는 이미 `:domain:avoidance` 를 의존하므로 주입이 자연스럽다. 출력 형태는 기존 적재 형태(`FoodAvoidanceItem`: code + inclusion_percent)와 1:1. 3-API fan-out·종합은 계약 뒤에 숨은 구현 상세라 시그니처에 드러나지 않는다.
- **Alternatives considered**: 구현이 카탈로그를 자체 보유 — 기각: 성분 목록의 단일 출처(DB)와 드리프트 발생(안전 직결 데이터).

## R6. 맵기 범위 — 0..10

- **Decision**: `FoodDescriptionContent.spiciness` 는 `Int`, init 에서 0..10 강제.
- **Rationale**: 회원 맵기 선호가 0~10 스케일(kb-158 — -1 은 "미설정" 센티널로 범위 밖)이므로 음식 맵기도 같은 스케일로 두면 선호 매칭 시 변환이 없다. 센티널(-1)은 "생성 전" 상태 표현이므로 생성 결과 DTO 에는 등장할 수 없다 — 범위 검증이 이를 강제한다.
- **Alternatives considered**: 범위 미검증 — 기각: LLM 응답 특성상 범위 밖 값이 실제로 온다. 계약 경계에서 잡는 게 가장 싸다.

## R7. 비용 계량(KB-155) — 계약 밖

- **Decision**: 계약 시그니처에 토큰·비용 메타를 넣지 않는다.
- **Rationale**: 기존 패턴대로 구현부(`:infra:llm`)가 `LlmCallCostIncurred` 를 발행해 원장에 적재한다 — 호출자는 비용을 모른다. 스펙 Assumptions 에서 명시적으로 범위 밖.

# 0004. research 바운디드 컨텍스트 신설 — 미스 메뉴 조사·종합, 배치 트리거

- **상태**: Accepted <!-- 본문의 패키지 표기(com.meogo.domain.research 등)는 ADR-0005에서 보완(com.meogo.api.research로 통일) -->
- **날짜**: 2026-06-27
- **관련**: [ADR-0003](./0003-pretranslated-batch-menu-pipeline.md), [meogo-conventions](../architecture/meogo-conventions.md), [domains/research](../architecture/domains/research.md), [domains/scan](../architecture/domains/scan.md), [domains/food](../architecture/domains/food.md), 헌법 원칙 II, [ADR-0005](./0005-unified-api-package-and-presentation-rename.md)

## Context

ADR-0003에서 메뉴 데이터 파이프라인을 사전 번역 배치 모델로 바꿨다 — 캐시 미스는 실시간 LLM 없이 결과 없음으로 응답하고, 재료 조사·9개국어 번역은 `meogo-batch`가 하루 1회 수행한다. 이때 다음이 미정으로 남았다.

- **미스 메뉴 대기열을 누가 소유하는가** — 여러 사용자가 같은 메뉴명을 미스로 만들 수 있으므로, 대기열은 정규화 메뉴명 기준으로 **중복 제거**되어야 한다. 이는 스캔 세션의 사실이 아니라 음식 카탈로그의 공백이다.
- **3개 LLM 응답을 종합해 음식의 메뉴명·재료 정보를 만드는 로직이 어느 경계에 사는가** — 이 로직에는 도메인 규칙(보수적 판단·재료 스코어·충돌 해소·신뢰 정책)이 있고, **사용자 API에서는 절대 호출하지 않으며 배치에서만** 트리거된다.
- 기존 `food` 컨텍스트가 LLM 추론 결과(`FoodSource`/`FoodInference`)까지 들고 있어, "검수된 카탈로그"와 "지식 획득 파이프라인"이 한 컨텍스트에 섞여 있었다.

제약: 도메인 자율성·영속 캡슐화(헌법 IV)와 "컨텍스트 조합은 `meogo-application`에서만"(헌법 II) 규칙을 지킨다. `api`와 `batch`는 별도 bootJar로 빌드·배포되지만 비즈니스는 재사용한다(ADR-0001).

## Decision

**`research` 바운디드 컨텍스트를 신설한다**(5번째 active BC). 모듈 경로 `:meogo-api:research`, 패키지 `com.meogo.domain.research`.

1. **`research`가 미스 메뉴 조사·종합을 소유한다.** 핵심 개념: `ResearchRequest`(정규화 메뉴명 키의 대기열, **dedup**·상태), `LlmResponse`(제공자별 원본 응답 VO), `SynthesizedFoodProfile`(3개 응답을 종합한 신뢰 결과). 종합 판단은 **순수 도메인 서비스(IO 없음)**로 둔다. `food`의 `FoodSource`/`FoodInference`(LLM 제공자·응답 요약·종합 결과·검수 사유 provenance)를 이 컨텍스트로 **이관**한다.

2. **`food`는 검수된 카탈로그로 축소한다.** Food/Ingredient/FoodIngredient/매핑/9개국어 번역/데이터 상태만 소유한다. LLM provenance는 더 이상 `food`의 책임이 아니며, Food는 자신을 만든 research를 **ID로만** 참조한다(컨텍스트 간 전체 객체 비참조 — 헌법 II).

3. **`scan`은 대기열을 소유하지 않는다.** scan은 스캔 사건·바운딩박스·이력·횟수 제한·결과 스냅샷의 주인이고, 미스는 "결과 없음" 스냅샷으로 기록만 한다. 미스 적재는 `meogo-application`이 `research`에 등록한다.

4. **LLM 병렬 호출이라는 IO는 `application`이 core port로 수행한다.** 유스케이스(예: `ProcessPendingResearch`)가 큐 읽기 → LLM 병렬 호출(`:meogo-api:infra` 어댑터) → `research` 종합 정책 호출 → `food` 저장을 조율한다(컨텍스트 조합은 application에서만).

5. **`meogo-batch`는 얇은 트리거다.** 하루 1회 스케줄/Job이 위 application 유스케이스를 **호출만** 한다(ADR-0001대로 위성 앱). 비즈니스 로직을 Job 안에 두지 않는다.

6. **배치 전용 조합 로직의 배치 규칙.** 이 유스케이스는 사용자 API가 호출하지 않으므로, `:meogo-api:application` 안에 두되 **전용 패키지로 격리하고 ArchUnit으로 web 진입점의 의존을 금지**한다. `:meogo-api:api`가 이 코드를 컴파일하는 비용(아티팩트 변경)은 MVP에서 감수한다. **분리 트리거** — ① 배치 전용 유스케이스가 여럿으로 늘 때, ② 독립 배포 CD가 엄격해 api 불필요 재배포가 실비용일 때, ③ 배치·api가 같은 application 서비스를 공유하기 시작할 때 — 중 하나가 발생하면 배치 전용 application 모듈(또는 ADR-0001이 적어둔 최상위 `apps/` 리네임)로 승격한다.

## Alternatives Considered

- **종합 로직을 `meogo-batch` Job 안에 구현** — 배치만 쓰니 직관적이나, (a) 도메인 로직이 실행 앱에 묻혀 배치 부팅 없이 테스트 불가, (b) 컨텍스트 조합이 application 밖에서 일어나 헌법 II 위반, (c) 온디맨드 워커 등 다른 트리거 추가 시 재작성. 비채택 — 배치는 트리거, 로직은 api 쪽.
- **대기열을 `scan`이 소유** — 스캔=요청 이벤트라 직관적이나 세션마다 중복 적재돼 dedup 책임이 애매. 대기열은 메뉴명 단위(카탈로그 공백)라 `research`가 맞다.
- **별도 컨텍스트 없이 `food` + `application`에만 분산** — 종합 정책·큐·provenance가 갈 곳이 없어 `food`가 다시 비대해진다. 지식(food)과 지식 획득(research)을 나누는 편이 깨끗.
- **즉시 배치 전용 application 모듈 분리** — 결합은 가장 깨끗하나 MVP(배치 유스케이스 1개)엔 과함. 패키지 격리+ArchUnit으로 충분, 트리거 충족 시 승격.

## Consequences

- **좋음**: 지식(`food`=검수된 카탈로그)과 지식 획득(`research`=조사·종합 파이프라인)이 분리돼 각자 단순해진다. 종합 정책이 순수 도메인 서비스라 단위 테스트가 쉽다. 대기열이 메뉴명 단위로 dedup된다. 배치는 얇게 유지되고 ADR-0001 구조를 그대로 쓴다. "사용자 API에서 호출 안 함"이 트리거 위치(배치 전용)로 자연히 보장된다.
- **트레이드오프**: active BC가 4→5로 늘어 모듈·문서가 하나 더 생긴다. 배치 전용 유스케이스가 공유 `:meogo-api:application`에 있어 변경 시 `api` 아티팩트도 바뀐다(behavior 변화는 없음). 경계는 패키지+ArchUnit으로만 강제된다(컴파일 분리는 아님).
- **후속/리스크**: ① ArchUnit 규칙 작성(web 진입점 → 배치 전용 패키지 의존 금지, research↔다른 도메인 직접 의존 금지). ② 분리 트리거 도달 모니터링. ③ `ResearchRequest` 영속·중복 제거·재시도·부분 성공 정책. ④ 3개 응답 종합 알고리즘은 여전히 미결정(ADR-0003 §관련 미결정). ⑤ 헌법 원칙 II의 BC 열거(`{food,member,scan,avoidance}`)에 `research` 추가 — 정식 버전 범프는 `speckit-constitution`으로 후속.

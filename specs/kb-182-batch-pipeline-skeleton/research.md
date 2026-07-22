# Research: 배치 콘텐츠 파이프라인 골격 재구축 (KB-182)

Technical Context 에 NEEDS CLARIFICATION 은 없다. 아래는 설계 결정과 근거다.

## D1. 레거시 제거 범위 — 모듈 통째 삭제

**Decision**: `:domain:research` 모듈을 통째로 삭제한다(settings.gradle.kts include, 루트 build.gradle.kts jacocoAggregation, app/batch build.gradle.kts 의존 포함). `app/batch` 의 `scoring` 패키지(main 4 + test 4 파일)와 `application.yml` 의 `kbap.scoring.*` 블록도 삭제한다.

**Rationale**: `:domain:research` 소비자는 `:app:batch` scoring 패키지가 유일함을 grep 으로 확인했다. 3모델 앙상블·합의 집계(ConsensusEnsembleAggregator·EnsemblePolicy 등)는 "LLM 호출당 소량, 단일 모델" 방향(프로젝트 메모리·티켓)과 맞지 않는다. 부분 수선보다 삭제가 diff 도 작고 혼란도 없다.

**Alternatives considered**: research 모듈을 빈 placeholder 로 유지(`:domain:review` 처럼) — 후속 태스크(KB-183·186)가 프롬프트/파싱 로직을 어디 둘지는 그 태스크의 결정 사항이므로 빈 모듈을 미리 남길 이유가 없다(YAGNI). 필요해지면 그때 다시 만든다.

## D2. INCOMPLETE 조회 창구 — FoodScoringSource 를 FoodContentBatchService 로 대체

**Decision**: `FoodScoringSource` 를 삭제하고 `:domain:food` 에 `FoodContentBatchService`(`@Service` + `internal constructor`)를 신설한다. 조회는 **id 키셋(cursor) 방식**: `getIncompleteFoods(afterId: Long?, size: Int)` — `content_status='INCOMPLETE' and id > :afterId order by id asc limit :size`.

**Rationale**: 기존 `nextChunk(page, size)` 는 page 번호 오프셋 방식인데, 처리된 음식이 READY 로 빠져나가면 INCOMPLETE 필터 기준 페이지가 밀려 건너뜀이 생기고, 반대로 실패 건이 남으면 page 0 재조회가 무한 루프가 된다. 키셋(마지막 처리 id 이후)은 두 문제를 모두 없앤다 — 한 실행에서 각 음식을 정확히 1회 방문하고, 실패 건은 다음 실행에서 재시도된다. 리포지토리 `internal` 경계와 `@Import` 조립 패턴은 기존 그대로 계승한다.

**Alternatives considered**: (1) FoodScoringSource 이름 유지·수정 — "scoring" 이 죽은 개념이라 이름이 거짓말이 된다. (2) page 오프셋 유지 — 위 결함. (3) `FoodService` 에 메서드 추가 — FoodService 는 도메인 서비스 그래프(타 도메인 의존)를 끌고 와 배치 컨텍스트에 올릴 수 없다(기존 주석의 분리 사유 그대로).

## D3. READY 전이 규칙 — Food 도메인 메서드, 엔티티 자기 상태로 완결 (2026-07-21 개정: #82 이후 파라미터 제거)

**Decision**: `Food` 에 전이 메서드를 둔다: `fun transitionToReadyIfComplete(): Boolean`. 완비 판정 = ① `!needsImage()`(imageRef 비-blank) ② `!needsDescription()`(비-blank·placeholder 아님) ③④ `!needsNameTranslations() && !needsDescriptionTranslations()`(9개 대상 언어 완비) ⑤ `!needsAvoidanceMapping()`(`avoidanceSubstances` JSON 비어있지 않음). 전부 만족 시 `contentStatus = READY` 후 true, 아니면 상태 불변 false. 이미 READY 면 true(멱등).

**Rationale**: 판정 로직은 도메인 소유(FR-003). develop #82 이 기피성분을 별도 테이블에서 **food 행의 JSON 컬럼**(`avoidanceSubstances: List<FoodAvoidanceItem>`)으로 이관하면서, 매핑이 food 스냅샷에 함께 실려 온다 — 초기 설계의 "별도 테이블 EAGER 연관 스냅샷 불일치" 문제가 사라졌다. 그래서 `hasAvoidanceMapping` 파라미터를 제거하고 엔티티가 `needsAvoidanceMapping()`으로 자기 상태를 직접 본다(모든 걸 food 행에서 관리하는 방향과 정합). 9개 언어 완비 기준은 헌법 V(사전 번역 정책)와 KB-96 의 "9언어 번역 완비" 판정을 계승한다.

**Alternatives considered**: (1) `hasAvoidanceMapping` 파라미터 주입(초기 안) — 기피성분이 별도 테이블이던 시절엔 스냅샷 불일치 회피용으로 필요했으나, #82 의 JSON 컬럼 이관으로 불필요해져 제거. (2) 번역 non-empty 만 요구 — "완성만 노출" 보장이 약해진다(부분 번역 노출). (3) spiciness 를 게이트에 포함 — 티켓 DoD 가 콘텐츠 3필드+기피성분으로 확정했고, spiciness 는 기본 0 이 유효값이라 완비 판정 불가(KB-209 에서 기피성분과 같은 호출로 채움).

## D4. 러너 골격 — 평범한 순차 루프 (2026-07-21 최종, 멘토 조언으로 Spring Batch 폐기)

**결정 변천**: (초안) 스텝 인터페이스 → (2차) 단일 잡 클래스 순차 루프(ApplicationRunner) → (3차) Spring Batch chunk Step → **(최종) 다시 평범한 순차 루프**. 멘토 조언: "스텝은 괜히 복잡, API 4번 그냥 순차 호출로 구성, 성능 문제면 이후 스레드풀+future/코루틴". Spring Batch 프레임워크·BATCH_* 메타 테이블의 무게가 현재 요구(단일 잡·수백 건)에 과하다는 판단.

**Decision**: `:app:batch` `content` 패키지에 **평범한 클래스 `FoodContentJob` + gated ApplicationRunner** 를 둔다(음식 단위 = Architecture A).

- `FoodContentJob.run()` — `getIncompleteFoods(afterId, chunkSize)` 키셋 청크 루프(빈 청크면 종료), 음식 1건마다 `try { process(food) } catch { 로그 후 다음 음식 }`(건 단위 격리). `afterId = chunk.last().id` 로 전진.
- `process(food)` — 4작업 **skip-if-done** 순차: `if (food.needsImage()) { generateImage; saveProgress }` … `mapAvoidance` 까지, 끝에 `completeContent(food)`. LLM 호출은 각 작업 메서드(트랜잭션 밖), 본문은 후속(KB-183·184·209)이 채운다.
- `ContentJobConfig` — `@Import(FoodContentBatchService)` + `@ConditionalOnProperty("kbap.batch.content.runner.enabled")` `ApplicationRunner { job.run() }` + `@Value` chunk-size.
- 작업별 독립 커밋(`saveProgress` REQUIRES_NEW)은 그대로 — 뒤 작업 실패해도 앞 작업 남아 다음 실행에서 실패 작업만 재시도. chunk-size 는 DB 조회 배치 크기(기본 10).

**Rationale**: 잡 하나·순차 처리엔 평범한 for 루프가 가장 단순·명료하다. 재시작·이력이 필요하면 상태가 이미 `content_status`·작업별 필드에 있어 다음 실행이 미완만 재처리한다(프레임워크 메타 테이블 불필요). 병렬화가 실제 성능 요구가 되면 그때 청크 단위 스레드풀(JDK21 가상스레드/future 또는 코루틴)로 확장한다.

**Alternatives considered**: (1) Spring Batch chunk Step(3차 안) — 재시작·실행이력·스킵 관측성을 공짜로 주지만 `BATCH_*` 6 메타 테이블(api Flyway)·chunk 빌더·fault-tolerant 배선이 현재 규모에 과하고, 병렬화도 Batch 의 multi-threaded step 을 강제해 멘토가 말한 future/코루틴과 안 맞아 폐기. (2) 작업별 Step 4개(Architecture B) — 음식 1건의 4작업이 4 Step 에 분산돼 원자성이 깨져 폐기. (3) commit-interval=1 원자 저장 — 뒤 작업 실패 시 앞 작업까지 롤백돼 비싼 LLM 재호출. 작업별 독립 커밋(saveProgress)이 이를 없앰.

## D5. 설정 키 — kbap.batch.content.*

**Decision**: `kbap.batch.content.chunk-size`(기본 10)·`kbap.batch.content.runner.enabled`(기본 false). `kbap.scoring.*` 블록은 삭제. `kbap.llm.*` 블록과 `:infra:llm` 의존은 유지.

**Rationale**: 실행 인자/환경변수로 재배포 없이 덮어쓰는 기존 운영 방식 그대로(FR-006). LLM 설정·의존은 KB-183 이 바로 사용하므로 남긴다 — 미사용 상태로 잠깐 남는 것이 지웠다 되살리는 것보다 싸다.

**Alternatives considered**: `@ConfigurationProperties` 클래스 — 설정값 2개에 과하다. `@Value` 2개로 충분.

## D6. contracts/ 생략

**Decision**: contracts/ 디렉터리를 만들지 않는다.

**Rationale**: 이 기능은 외부 노출 인터페이스(API·CLI·이벤트)가 없다. 유일한 계약은 `FoodContentStep` seam 과 `FoodContentBatchService` 시그니처인데, 이는 data-model.md 에 함께 기술한다.

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

## D4. 러너 골격 — Spring Batch chunk-oriented Step (2026-07-21 재개정)

**결정 변천**: (초안) 스텝 인터페이스 + 빈 리스트 → (2차) 단일 잡 클래스 + 작업별 메서드(ApplicationRunner 루프) → **(확정) Spring Batch chunk-oriented Step 1개**. 사용자가 3-API 종합 등 성분 조사 로직의 복잡도를 근거로 재시작·실행 이력·스킵 관측성을 위해 Spring Batch 채택을 지시(2026-07-21).

**Decision**: `:app:batch` `content` 패키지에 **Job 1개 → chunk-oriented Step 1개**를 둔다(음식 단위 = Architecture A).

- `IncompleteFoodItemReader`(`ItemStreamReader<Food>`) — `getIncompleteFoods(lastReadId, pageSize)` 키셋 페이지 리더. 복원 지점은 "마지막으로 넘긴 음식 id"(ExecutionContext) 라 버퍼 미처리분을 건너뛰지 않는다.
- `FoodContentItemProcessor`(`ItemProcessor<Food, Food>`) — 음식 1건의 4작업을 **작업별 메서드**(`generateImage`·`generateDescription`·`translateContent`·`mapAvoidance`)로 수행(LLM 호출은 여기 = 트랜잭션 밖). 본문은 후속(KB-183·184·209)이 채운다. `mapAvoidance` 는 KB-209 에서 API 3개 호출·종합으로 `food.avoidanceSubstances` 를 채운다(boolean 반환 없음 — food 행에 직접).
- writer(`ItemWriter<Food>`) — `completeContent(food)` 로 저장·전이.
- Processor 는 **작업별 skip-if-done** — `food.needsImage()/needsDescription()/needsNameTranslations()/needsDescriptionTranslations()` 로 이미 된 작업은 LLM 호출을 건너뛰고, 한 작업 끝나면 `saveProgress`(**REQUIRES_NEW 즉시 커밋**)로 결과를 남긴다.
- Step: chunk-size = `kbap.batch.content.chunk-size`(기본 10), `faultTolerant().skip(Exception).skipLimit(MAX)` + SkipListener 로그(건 단위 격리·다음 실행에서 실패 작업만 재시도).
- Job: `RunIdIncrementer`(야간 반복 재실행). 부팅 자동 실행은 `spring.batch.job.enabled=false` 기본, 실행 시 인자로 켠다.

**Rationale (A' — 작업별 skip-if-done + 독립 커밋)**: 각 작업이 결과를 즉시 개별 커밋하므로 (1) 해야 하는 음식만 LLM 을 태우고, (2) 뒤 작업이 실패해도 앞 작업 결과가 롤백되지 않아 다음 실행에서 **실패한 작업만 재시도**한다. (3) 독립 커밋이라 청크가 롤백·재스캔돼도 이미 된 작업은 needsX=false 로 건너뛰어 LLM 중복이 없어 **chunk-size 를 크게(10) 잡아도 안전**하다. "음식 1건 = 트랜잭션 1개(전부 롤백)" 원자성은 폐기 — 반쯤 찬 음식 노출 방지는 READY 게이트가 담당하므로 잃는 것이 없다. Spring Batch 가 재시작·실행 이력·스킵 카운트를 제공해 다중 외부 API 종합 잡의 관측성/복구성을 확보한다.

**메타데이터**: 배치는 `flyway off`(스키마 owner=api) 라 `BATCH_*` 6테이블을 **api Flyway 마이그레이션**이 생성한다(`V2026.07.21…__spring_batch_metadata.sql`, spring-batch-core schema-mysql.sql 원본). 배치 main 은 `initialize-schema=never`, 배치 test 는 Flyway off 환경이라 `initialize-schema=always`(Batch 자체 initializer 가 Testcontainer 에 생성).

**Alternatives considered**: (1) ApplicationRunner + 평범한 순차 루프(2차 안) — 더 단순하지만 재시작·이력·스킵 관측성이 없어 부적합(사용자 판단). (2) 작업별 Step 4개(Architecture B) — 작업이 독립 Step·작업별 이력이 되지만 음식 1건의 4작업이 4 Step 에 분산돼 리더 4개(작업별 미완료 조회)로 늘고, A' 의 작업별 skip-if-done 이 같은 "실패 작업만 재시도"를 단일 Step 으로 주므로 폐기. (3) commit-interval=1 원자 저장(초기 A 안) — 뒤 작업 실패 시 앞 작업까지 롤백돼 재실행에서 비싼 LLM 을 다시 태움. 작업별 독립 커밋(A')이 이를 없애고 chunk-size 를 키울 수 있게 함.

## D5. 설정 키 — kbap.batch.content.*

**Decision**: `kbap.batch.content.chunk-size`(기본 10)·`kbap.batch.content.runner.enabled`(기본 false). `kbap.scoring.*` 블록은 삭제. `kbap.llm.*` 블록과 `:infra:llm` 의존은 유지.

**Rationale**: 실행 인자/환경변수로 재배포 없이 덮어쓰는 기존 운영 방식 그대로(FR-006). LLM 설정·의존은 KB-183 이 바로 사용하므로 남긴다 — 미사용 상태로 잠깐 남는 것이 지웠다 되살리는 것보다 싸다.

**Alternatives considered**: `@ConfigurationProperties` 클래스 — 설정값 2개에 과하다. `@Value` 2개로 충분.

## D6. contracts/ 생략

**Decision**: contracts/ 디렉터리를 만들지 않는다.

**Rationale**: 이 기능은 외부 노출 인터페이스(API·CLI·이벤트)가 없다. 유일한 계약은 `FoodContentStep` seam 과 `FoodContentBatchService` 시그니처인데, 이는 data-model.md 에 함께 기술한다.

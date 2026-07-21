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

## D3. READY 전이 규칙 — Food 도메인 메서드, 매핑 존재는 파라미터로 주입

**Decision**: `Food` 에 전이 메서드를 둔다: `fun transitionToReadyIfComplete(hasAvoidanceMapping: Boolean): Boolean`. 완비 판정 = ① `imageRef` 존재(비-blank) ② `description` 이 비-blank 이고 placeholder("설명 준비 중")가 아님 ③ `nameTranslations`·`descriptionTranslations` 가 9개 대상 언어 코드를 모두 포함 ④ `hasAvoidanceMapping == true`. 전부 만족 시 `contentStatus = READY` 후 true, 아니면 상태 불변 false. 이미 READY 면 true(멱등, 상태 불변).

**Rationale**: 판정 로직은 도메인 소유(FR-003). 기피성분 매핑은 별도 테이블이고 `Food.avoidanceSubstances` 는 읽기 전용 EAGER 연관이라 **같은 트랜잭션에서 방금 쓴 매핑이 연관에 반영되지 않는다** — 스냅샷 불일치를 피하려고 매핑 존재 여부는 호출자(방금 쓴 쪽이 안다)가 boolean 으로 주입한다. 9개 언어 완비 기준은 헌법 V(사전 번역 정책)와 KB-96 의 "9언어 번역 완비" 판정을 계승한다.

**Alternatives considered**: (1) 연관 컬렉션으로 자가 판정 — 위 스냅샷 문제로 방금 완성한 음식이 전이되지 않는 버그 소지. (2) 번역 non-empty 만 요구 — "완성만 노출" 보장이 약해진다(부분 번역 노출). (3) spiciness 를 게이트에 포함 — 티켓 DoD 가 콘텐츠 3필드+기피성분으로 확정했고, spiciness 는 기본 0 이 유효값이라 완비 판정이 불가능(KB-209 에서 기피성분과 같은 호출로 채움).

## D4. 러너 골격 — 단일 잡 + 작업별 메서드 + 건 단위 실패 격리 (2026-07-21 개정: 스텝 인터페이스 제거)

**Decision**: `:app:batch` 의 `content` 패키지에 **잡 클래스 하나**만 둔다. 스텝 인터페이스·플러그인 빈 구조는 만들지 않는다(사용자 지시 — 과한 추상화 지양).

- `FoodContentJob` — 루프: `getIncompleteFoods(afterId, chunkSize)` 로 청크 소진까지 반복, 음식 1건마다 `try { 작업별 메서드 순차 호출(트랜잭션 밖) → service.completeContent(food, hasAvoidanceMapping) } catch { 로그 후 다음 음식 }`. 작업별 메서드는 잡 안의 평범한 메서드 4개 — `generateImage(food)`·`generateDescription(food)`·`translateNames(food)`·`mapAvoidance(food)` — 로 LLM 호출을 태스크별로 구분하고, 이번 범위에선 본문이 비어 있다(후속 KB-183·184·209 가 각 메서드 본문을 채운다).
- `ContentJobConfig` — `@Import(FoodContentBatchService)` 조립, `@ConditionalOnProperty("kbap.batch.content.runner.enabled")` 게이팅, `@Value("\${kbap.batch.content.chunk-size:10}")` 청크 설정. 기존 ScoringRunnerConfig 게이팅 패턴 계승.

**Rationale**: 작업 실행을 트랜잭션 밖에 두고 저장+전이만 짧은 트랜잭션(`FoodContentBatchService.completeContent`)으로 묶어 "외부 호출은 트랜잭션 밖" 제약을 골격 구조로 강제한다. 실패 격리는 음식 단위 try/catch 하나로 충분하다. 후속 태스크가 꽂힐 "자리"는 인터페이스가 아니라 메서드 4개다 — 구현체가 하나뿐일 인터페이스는 만들지 않는다.

**Alternatives considered**: (1) `FoodContentStep` fun interface + 빈 리스트 주입 — 초안이었으나 폐기: 소비자가 잡 하나뿐이고 스텝 교체·조합 요구가 없어 추상화 비용만 남는다. (2) Spring Batch 프레임워크 — Job/Step/Chunk 추상화·메타 테이블이 현재 규모(단일 잡, 수백 건)에 과하다.

## D5. 설정 키 — kbap.batch.content.*

**Decision**: `kbap.batch.content.chunk-size`(기본 10)·`kbap.batch.content.runner.enabled`(기본 false). `kbap.scoring.*` 블록은 삭제. `kbap.llm.*` 블록과 `:infra:llm` 의존은 유지.

**Rationale**: 실행 인자/환경변수로 재배포 없이 덮어쓰는 기존 운영 방식 그대로(FR-006). LLM 설정·의존은 KB-183 이 바로 사용하므로 남긴다 — 미사용 상태로 잠깐 남는 것이 지웠다 되살리는 것보다 싸다.

**Alternatives considered**: `@ConfigurationProperties` 클래스 — 설정값 2개에 과하다. `@Value` 2개로 충분.

## D6. contracts/ 생략

**Decision**: contracts/ 디렉터리를 만들지 않는다.

**Rationale**: 이 기능은 외부 노출 인터페이스(API·CLI·이벤트)가 없다. 유일한 계약은 `FoodContentStep` seam 과 `FoodContentBatchService` 시그니처인데, 이는 data-model.md 에 함께 기술한다.

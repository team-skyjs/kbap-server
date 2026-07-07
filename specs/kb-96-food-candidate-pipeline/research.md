# Research: 음식 candidate 스테이징 파이프라인 골격 (KB-96)

**Date**: 2026-07-07 | **Plan**: [plan.md](./plan.md) | **근거 ADR**: [0012](../../docs/adr/0012-food-candidate-staging-promotion-pipeline.md)·[0013](../../docs/adr/0013-batch-bulk-persistence-io-exposed.md)

아래 결정들은 스펙 FR·SC 와 ADR-0012(스테이징→승격)·ADR-0013(JPA-first·IO 규칙)을 전제로 내렸다.

## R1. candidate 테이블 소유·위치·스키마

**Decision**: candidate 는 `:core:research` 가 소유하는 도메인(`com.meogo.core.research.candidate.FoodCandidate`), 영속은 `:infra:persistence` 의 `com.meogo.infra.persistence.research.FoodCandidateJpaEntity`(`BaseEntity` 상속). 테이블 `food_candidate` 컬럼: `korean_name`(자연키·조회), `description`(ko, nullable), `description_translations`(JSON, 기본 `{}`), `substance_mapping`(JSON 스냅샷 `[{code,percent}]`, 기본 `[]`), `published_food_id`(BIGINT nullable). 성분 매핑은 **자식 테이블이 아니라 JSON 스냅샷**으로 둔다.

**Rationale**: research 는 미스 메뉴 조사·종합 파이프라인 컨텍스트(ADR-0004)라 "제작 중 음식"의 자연스러운 주인이다. 성분 매핑을 JSON 스냅샷으로 두면 스테이징에 FK·조인·별도 엔티티가 없어 부분 업데이트(컬럼 1개 교체)와 멱등 재적재가 단순하다 — 승격 시 이 스냅샷을 읽어 `food_avoidance_substance` 로 정규화하면 된다. `food` 의 JSON 번역 컬럼(KB-48)과 동형이라 매핑 코드도 재사용 흐름이다.

**Alternatives considered**:
- **성분 매핑 자식 테이블**(`food_candidate_avoidance_substance`): 정규화되지만 스테이징 단계에서 FK·컬럼-스코프 부분 업데이트가 번거롭고, 승격 전까지의 정규화 이득이 없다. 기각.
- **candidate 를 `food` 에 in-place + publish 플래그**: 서빙 테이블에 미완성 행이 섞임(ADR-0012 에서 기각).
- **candidate 를 `:core:food` 소유**: food 는 완성 서빙 도메인이라 "제작 중" 개념을 섞으면 컨텍스트가 흐려진다. research 소유가 경계에 맞다.

## R2. 완성 판정 위치·방식

**Decision**: 완성 판정은 도메인에 둔다 — `FoodCandidate.isComplete(): Boolean` = `description != null && descriptionTranslations.size == 9 && substanceMapping.isNotEmpty() && publishedFoodId == null`. 승격 대상 조회는 리포지토리 포트 `findPromotable(page, size)` 가 **DB 조회 술어**로 1차 필터(`published_food_id IS NULL AND description IS NOT NULL AND JSON_LENGTH(description_translations)=9 AND JSON_LENGTH(substance_mapping)>0`)하고, 배치가 도메인 `isComplete()` 로 재확인한다.

**Rationale**: 별도 상태머신(DRAFTING/READY/PUBLISHED 컬럼)을 두지 않고 **완성 여부를 데이터에서 유도**하는 게 가장 단순하다(상태 전이 관리·드리프트 없음). 9는 콘텐츠 언어 정책의 대상 언어 수(`LanguageCode` 에서 KO 제외, 원칙 V). DB 술어로 후보를 좁혀 대량에서 전량 로드를 피하고, 도메인 `isComplete()` 로 의미를 단일 출처화한다.

**Alternatives considered**:
- **명시 상태 컬럼 + 전이**: enrichment 마다 상태를 갱신·정합 유지해야 해 실수 여지가 크다. 유도 방식이 단순·견고. 기각.
- **번역 언어 집합을 값으로 검증(9개 정확 일치)**: 길이 9 + 키 유효성까지 도메인에서 검증(초과·중복·비지원 코드 배제). 채택(도메인 `isComplete` 내부에서 키 검증 포함).

## R3. 컬럼-스코프 부분 업데이트 + IO 규칙 (JPA-first)

**Decision**: enrichment(후속 KB-54·KB-94)와 승격 마킹의 쓰기는 **엔티티 통째 merge 가 아니라 컬럼-스코프 `@Modifying @Query` 벌크 UPDATE** 로 한다. 예: `UPDATE FoodCandidateJpaEntity c SET c.substanceMapping = :snap WHERE c.id = :id`(성분), `... SET c.descriptionTranslations = :tr ...`(번역), `... SET c.publishedFoodId = :fid ...`(승격). 균일 값 상태 변경은 `WHERE id IN (:ids)` 단일문, 대량은 JDBC 배치(`rewriteBatchedStatements=true`).

**Rationale**: `@Modifying` 은 엔티티를 persistence context 로 로드하지 않고 직접 SQL 을 실행 → 1차 캐시 적체·dirty checking 을 우회(ADR-0013 의 "엔티티 관리 비용" 회피를 ORM 교체 없이 달성). 컬럼-스코프라 스코어링(성분)과 설명(번역) 잡이 같은 행을 동시에 갱신해도 서로의 컬럼을 덮지 않아 정합성이 실행 타이밍에 의존하지 않는다(SC-004). KB-96 자체는 이 seam(컬럼별 update 메서드 시그니처)을 포트·어댑터에 **미리 박아** KB-54·KB-94 가 소충돌 없이 병렬 구현하게 한다.

**Alternatives considered**:
- **엔티티 로드→setter→save(merge)**: 편하지만 전 컬럼을 다시 써 동시 실행 시 타 잡 컬럼을 덮고, context 비용도 그대로. 기각.
- **Exposed/JdbcClient 즉시 도입**: ADR-0013 에서 후속으로 미룸(Boot 4 호환·이중 스택 비용). `@Modifying` 이 context 회피를 이미 제공.

## R4. 승격: food 업서트·멱등·트랜잭션

**Decision**: 승격 배치는 `findPromotable` 로 완성 candidate 를 페이지 조회 → 각 candidate 를 `Food` 도메인으로 조립 → `FoodRepository.save(food)` 로 **`korean_name` 기준 업서트**(있으면 update, 없으면 insert) → `food_avoidance_substance` 를 스냅샷으로 재적재 → `FoodCandidateRepository.markPublished(candidateId, foodId)`. **음식 1건 = 트랜잭션 1개**. `FoodRepository` 에 `save(food): Food` 포트 추가, 어댑터는 기존 JPA 패턴으로 구현(`FoodJpaEntity.from(domain)` 컴패니언 추가).

**Rationale**: `food.korean_name` UNIQUE(기존 제약) 위에서 업서트 + candidate 의 `published_food_id` 링크로 **이중 멱등**(재실행·부분 실패 안전, SC-003/SC-005). 음식 단위 트랜잭션이라 한 건 실패가 나머지를 막지 않고 실패분은 다음 실행 대상으로 남는다(FR-006). 승격은 외부 호출이 없는 순수 DB 라 짧은 트랜잭션으로 헌법 Additional Constraints(외부 호출을 TX 안에 오래 두지 않음)에 부합.

**Alternatives considered**:
- **전량 한 트랜잭션**: 한 건 실패가 전체 롤백 → 부분 실패 복원(FR-006) 위반. 기각.
- **`published_food_id` 없이 korean_name 존재로만 멱등**: 승격 여부와 "이미 food 에 있음"(시드 등)이 구분 안 됨. 링크 컬럼으로 승격 완료를 명확히. 채택.
- **대량 INSERT 배치**: IDENTITY 로 Hibernate INSERT 배치가 무력화되나, 골격 규모(픽스처·초기)에선 건별 save 로 충분. 규모 임계에서 JDBC 배치 후속(ADR-0013).

## R5. 컨텍스트 경계 이관 (research → food)

**Decision**: 승격은 `:app:batch` 가 `FoodCandidateRepository`(research)·`FoodRepository`(food) 두 포트를 주입받아 조합한다. candidate(research 도메인) → `Food`(food 도메인) 변환은 배치 계층에서 **스냅샷 값**(korean_name: String, ko/번역: String·Map, 성분: `AvoidanceSubstanceCode`+percent)으로 조립하며, 두 도메인 객체를 서로 import 하지 않는다.

**Rationale**: 원칙 II — 컨텍스트 조합은 조율 계층(배치는 top-layer 로 조합 허용), 컨텍스트 간은 ID·코드·스냅샷으로 참조. 성분 코드는 이미 공용 vocabulary(`AvoidanceSubstanceCode`)라 타입 안전 참조가 된다(kernel/food 의 `AvoidanceSubstanceCodeRef`).

**Alternatives considered**:
- **research 가 food 를 직접 의존해 변환**: 도메인 간 직접 결합(원칙 II 위반). 기각.

## R6. candidate 생성 스텝·ko 설명 출처

**Decision**: candidate 생성 스텝은 음식명(korean_name) + ko 설명을 확보해 `food_candidate` 행을 만든다(번역·성분 컬럼은 기본 빈 값). KB-96 범위에서는 **생성 포트/경로만 제공**하고, 실제 메뉴 발견 소스(스캔 연동)·ko 설명 저작 파이프라인은 최소 구현(또는 픽스처 주입)으로 둔다. ko 설명은 설명 번역(KB-94)의 **입력 전제**이므로 생성 시점에 존재한다고 본다.

**Rationale**: Q2(설명 잡은 9언어 번역만) 결정에 따라 ko 설명은 candidate 의 선행 입력이다. 골격은 "생성 → enrichment → 승격"의 연결점을 열어두되, 생성 소스의 완전 자동화는 스캔/저작 티켓으로 분리한다(범위 관리).

**Alternatives considered**:
- **ko 설명도 이 골격에서 LLM 생성**: 범위 확장·KB-94 와 중복. 기각(별도 티켓).

## R7. 픽스처 e2e 전략 + Testcontainers

**Decision**: 골격 검증은 candidate 생성 → **성분·번역을 픽스처로 심기**(포트의 컬럼-스코프 update 메서드로) → 승격 → `food`·`food_avoidance_substance` 적재 확인을 MySQL Testcontainers(`:infra:persistence` testFixtures `MySqlContainerConfig`)에서 BehaviorSpec 으로 검증한다. 완성 판정(도메인)은 순수 단위 테스트, `@Modifying`·업서트·멱등은 통합 테스트.

**Rationale**: KB-54·KB-94(실제 enrichment)가 아직 없으니 그 부분을 픽스처로 대체해도 골격의 계약(완성 게이트·멱등 승격·부분 실패·컬럼 보존)을 완결 검증할 수 있다(FR-009/SC-007). Flyway 는 테스트에서 비활성(H2 아님·Testcontainers)이라 `food_candidate` 스키마는 테스트 부트스트랩(Hibernate ddl 또는 테스트 마이그레이션)로 준비하고, 실제 Flyway SQL 은 로컬 MySQL 부팅으로 별도 검증(기존 관행).

**Alternatives considered**:
- **KB-54·KB-94 완료까지 대기**: 골격이 독립 검증·머지 불가 → 직렬 병목 심화. 픽스처로 선완결. 채택.

## R8. 영속 기술 — JPA-first (요약)

**Decision**: candidate·승격 어댑터는 JPA 로 구현(신규 스택 없음). 상세·대안은 [ADR-0013](../../docs/adr/0013-batch-bulk-persistence-io-exposed.md).

**Rationale**: 첫 골격 최소 비용(기존 BaseEntity·Testcontainers 재사용, Boot 4 호환 리스크 0), context 비용은 `@Modifying` 으로 회피, 포트 seam 으로 Exposed 후속 교체가 어댑터 국소 변경. 컴파일 타임 쿼리 안전은 Testcontainers 통합 테스트로 대체 커버.

## 잔여 NEEDS CLARIFICATION

없음.

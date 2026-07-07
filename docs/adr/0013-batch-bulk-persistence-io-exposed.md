# ADR-0013: 배치 대량 영속·IO 전략 — JPA-first(@Modifying bulk) + IO 규칙, Exposed 후속

- **상태**: Proposed (2026-07-07) <!-- KB-96 구현·검증 시 Accepted 승격 -->
- **관련**: [ADR-0012](./0012-food-candidate-staging-promotion-pipeline.md)(candidate 스테이징 파이프라인) · [ADR-0008](./0008-modular-monolith-shared-domain.md)(원칙 IV 영속 캡슐화) · Jira KB-96·KB-54·KB-94 · [ADR-0006](./0006-central-persistence-adapter-and-decoupled-batch.md)(`:infra:persistence`)

## Context

candidate 파이프라인([ADR-0012](./0012-food-candidate-staging-promotion-pipeline.md))은 **대량·write-heavy·rich 도메인 행위 없는 데이터 처리 워크로드**다. 수천~수만 행을 청크로 훑어 부분 업데이트하고, 완성분을 대량 적재(승격)한다. 여기서 두 종류의 비용이 문제가 된다.

- **JPA/Hibernate 의 엔티티 관리 비용.** persistence context 1차 캐시에 관리 엔티티가 적체되어(대량 배치일수록 메모리·GC 압박), flush 마다 dirty checking 이 관리 엔티티 × 전 필드로 발생한다. `BaseEntity` 의 `id` 가 **IDENTITY(MySQL AUTO_INCREMENT)** 라 Hibernate 의 **INSERT 배치가 무력화**된다.
- **DB IO 라운드트립.** 단건 반복 UPDATE/INSERT 는 라운드트립이 행 수에 비례한다. 청크당 1회로 줄여야 한다.

동시에 쿼리 오류를 이른 시점에 잡고 싶은 요구가 있다. 후보는 (a) 기존 JPA 를 그대로 쓰되 벌크 경로만 최적화, (b) Exposed(Kotlin SQL DSL) 로 어댑터를 새로 구현, (c) jOOQ, (d) 순수 JdbcClient 였다. 제약: 이 프로젝트는 Spring Boot 4.1 로 매우 최신이라 서드파티 영속 스타터의 Boot 4 autoconfig 호환이 검증되지 않았고, 팀은 **첫 골격(KB-96)을 최소 비용으로 빠르게** 세우길 원한다.

## Decision

**candidate 파이프라인의 1차 구현은 JPA 로 간다. persistence context 비용은 hot-path 에서 `@Modifying` 벌크 쿼리로 회피하고, 아래 IO 규칙을 강제한다. Exposed 는 포트 seam 뒤 후속 최적화로 미룬다.**

- **JPA-first**: candidate 어댑터를 기존 JPA/`BaseEntity`·Spring Data 패턴으로 구현한다 — 빌드·테스트(Testcontainers) 자산을 그대로 재사용하고 Boot 4 호환 리스크가 없다.
- **persistence context 회피(hot-path)**: 대량·부분 업데이트는 엔티티를 로드하지 않고 **`@Modifying @Query`** 로 직접 `UPDATE` 한다(1차 캐시·dirty checking 우회). 즉 "엔티티 관리 비용" 문제를 ORM 교체 없이 벌크 쿼리로 해결한다.
- **IO 규칙 (기술 무관, JPA 로도 강제):**
  1. **컬럼-스코프 부분 업데이트** — 각 잡은 자기 컬럼만 `UPDATE`(엔티티/행 통째 merge 금지 → 동시에 도는 타 잡의 컬럼을 덮지 않음). 정합성을 실행 타이밍에 의존하지 않게 하는 핵심(ADR-0012).
  2. **청크당 라운드트립 최소화** — 균일 값은 `WHERE id IN (...)` 단일문, 행별 다른 값·대량 INSERT 는 JDBC 배치(`rewriteBatchedStatements=true`)로. 단건 반복 금지.
  3. **멱등** — 이미 채운/승격된 행은 조회 단계에서 제외, 승격은 자연 키(음식명) 업서트 + 승격 링크.
- **포트 seam 유지**: `FoodCandidateRepository` 등 포트는 순수하고, 어댑터 기술(JPA)은 `:infra:persistence` 구현 세부다. **규모·쿼리 안전이 실제로 정당화되면 어댑터만 Exposed(또는 jOOQ)로 교체**한다 — 상위(배치·도메인)는 불변.
- **컴파일 타임 쿼리 안전은 지금 포기**하고, MySQL Testcontainers 통합 테스트로 잘못된 쿼리를 머지 전에 검출한다(기존 전략 재사용).
- `food` 서빙 읽기(API 음식 상세)는 기존 JPA/도메인 복원을 유지한다(범위 밖).

## Alternatives Considered

- **Exposed 즉시 도입.** persistence context 제거 + 컴파일 타임 쿼리 안전을 바로 주지만, Exposed 빌드 셋업·`SpringTransactionManager` 브리지·**Boot 4 autoconfig 호환 리스크**·JPA 와의 이중 스택·Exposed 테이블 object 의 스키마 드리프트 관리가 붙는다. 첫 골격엔 과투자라 **포트 seam 뒤 후속**으로 미룬다(규모/쿼리 안전이 실제로 아플 때). context 비용은 그전까지 `@Modifying` 으로 회피된다.
- **jOOQ.** 코드젠으로 컴파일 타임 + 스키마 검증까지 주지만 코드젠 파이프라인·빌드 복잡도가 크다. 규모/안전 요구가 커지면 Exposed 와 함께 재검토.
- **순수 JdbcClient.** context-free 지만 JPA 패턴(엔티티·Testcontainers) 재사용 이점이 없고 SQL 이 문자열이다. `@Modifying` 이 재사용·context 회피를 둘 다 만족해 우선순위 낮음.
- **JPA 를 벌크 최적화 없이 그대로(엔티티 로드→수정→save).** 대량에서 persistence context·dirty checking 비용이 그대로다. `@Modifying` 벌크로 hot-path 만 최적화해 회피.

## Consequences

**+**
- 첫 골격(KB-96)이 가볍다 — 기존 JPA/BaseEntity/Testcontainers 자산 재사용, Boot 4 호환 리스크 0.
- persistence context 비용은 `@Modifying` 벌크로 회피, IO 규칙(컬럼-스코프·청크·멱등)은 기술 무관하게 그대로 강제.
- 포트 seam 덕에 Exposed/jOOQ 후속 교체가 어댑터 국소 변경으로 제한된다.

**−**
- **컴파일 타임 쿼리 안전이 없다**(JPQL 문자열) — MySQL Testcontainers 통합 테스트로 커버한다.
- **IDENTITY 로 인한 Hibernate INSERT 배치 무력화**는 남는다 — 승격 대량 INSERT 는 초기 규모에선 감수하고, 커지면 JDBC 배치/Exposed 로 최적화(후속).
- JPA + (후속)Exposed 이중 스택 가능성은 미뤄둔 상태다.

## 후속

- 규모(수천~수만 행 정례 처리)나 컴파일 타임 쿼리 안전 요구가 실제로 커지면, candidate·승격 어댑터를 **Exposed(또는 jOOQ)로 교체하는 후속 ADR**을 남긴다(포트 seam 덕에 상위 불변). 그때 `SpringTransactionManager` 브리지·Boot 4 호환·스키마 드리프트 대책을 함께 정한다.
- 승격 대량 INSERT 의 IDENTITY-배치 문제는 규모 임계에서 JDBC 배치로 우선 대응한다.

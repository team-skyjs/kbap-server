# ADR-0013: 배치 대량 영속·IO 전략 — candidate 파이프라인 어댑터는 Exposed + bulk IO 규칙

- **상태**: Proposed (2026-07-07) <!-- P1 구현·검증 시 Accepted 승격 -->
- **관련**: [ADR-0012](./0012-food-candidate-staging-promotion-pipeline.md)(candidate 스테이징 파이프라인) · [ADR-0008](./0008-modular-monolith-shared-domain.md)(원칙 IV 영속 캡슐화) · Jira KB-54·KB-94 · [ADR-0006](./0006-central-persistence-adapter-and-decoupled-batch.md)(`:infra:persistence`)

## Context

candidate 파이프라인([ADR-0012](./0012-food-candidate-staging-promotion-pipeline.md))은 **대량·write-heavy·rich 도메인 행위 없는 데이터 처리 워크로드**다. 수천~수만 행을 청크로 훑어 부분 업데이트하고, 완성분을 대량 적재(승격)한다. 이 워크로드에서 두 종류의 비용이 문제가 된다.

- **JPA/Hibernate 의 엔티티 관리 비용.** persistence context 1차 캐시에 관리 엔티티가 적체되어(대량 배치일수록 메모리·GC 압박), flush 마다 dirty checking 이 관리 엔티티 × 전 필드로 발생한다(O(n×필드) — 타깃 업데이트만 하는데 순수 낭비). 게다가 `BaseEntity` 의 `id` 가 **IDENTITY(MySQL AUTO_INCREMENT)** 라 Hibernate 의 **INSERT 배치가 무력화**된다(생성키를 건건이 받아야 해서). auto-flush 오버헤드도 있다.
- **DB IO 라운드트립.** 단건 반복 UPDATE/INSERT 는 라운드트립이 행 수에 비례한다. 청크당 1회로 줄여야 한다.

동시에, 쿼리 오류(컬럼 오타·타입 불일치)를 **가능한 이른 시점에** 잡고 싶다. 이 프로젝트는 MySQL Testcontainers 통합 테스트가 필수(KB-46)라 잘못된 SQL 은 머지 전 테스트에서 깨지지만, 팀은 **컴파일 타임 쿼리 안전**을 최우선 가치로 둔다.

## Decision

**candidate 파이프라인의 영속 어댑터는 JPA 가 아니라 Exposed(JetBrains Kotlin SQL DSL)로 구현하고, 아래 bulk IO 규칙을 강제한다.**

- **Exposed 채택 이유**: persistence context 가 없어(엔티티 라이프사이클·dirty checking·1차 캐시 제거) 대량 배치의 엔티티 관리 비용을 제거하고, 컬럼·타입을 Kotlin 테이블 object 로 선언해 **컴파일 타임에 쿼리 타입이 검증**된다. Spring 트랜잭션 통합은 `exposed-spring-boot-starter` 의 `SpringTransactionManager` 로 기존 `@Transactional` 경계에 브리지한다.
- **도메인은 ORM-free 유지**(원칙 IV). `FoodCandidateRepository` 등 포트는 순수하고, Exposed 는 `:infra:persistence` 어댑터 구현 안에만 갇힌다. JPA 냐 Exposed 냐는 어댑터 구현 세부이므로 모듈 경계·ArchUnit 은 그대로 유효하다.
- **bulk IO 규칙 (대량 hot-path):**
  1. **컬럼-스코프 부분 업데이트** — 각 잡은 자기 컬럼만 UPDATE 한다(엔티티/행 통째 갱신 금지 → 동시에 도는 타 잡의 컬럼을 덮어쓰지 않음). 정합성을 실행 타이밍에 의존하지 않게 하는 핵심(ADR-0012).
  2. **청크당 1 라운드트립** — 행마다 값이 다르면 batch update, 값이 균일하면 `WHERE id IN (...)` 단일문, 대량 INSERT 는 다중행으로.
  3. **`rewriteBatchedStatements=true`**(MySQL JDBC URL) — 배치가 실제로 라운드트립을 줄이도록. 승격 INSERT 는 IDENTITY 로 인한 배치 무력화를 피하려 JDBC 레벨 다중행 INSERT 로 수행.
  4. **멱등** — 이미 채운/승격된 행은 조회 단계에서 제외.
- **`food` 서빙 읽기(API 음식 상세)는 기존 JPA/도메인 복원을 유지**한다 — 이 결정은 **batch 파이프라인 어댑터에 국한**하며 전면 ORM 교체가 아니다.

## Alternatives Considered

- **JPA + `@Modifying` 벌크 쿼리 + `JdbcClient`.** persistence context 비용은 JdbcClient 로 피할 수 있고 신규 의존성도 0 이나, SQL 이 문자열이라 **컴파일 타임 체크가 없다**(오류가 테스트/런타임에만 드러남). Testcontainers 로 프로덕션 전에 잡히긴 하지만, 팀이 컴파일 타임 쿼리 안전을 최우선으로 선택해 Exposed 로 간다.
- **jOOQ(코드젠, 스키마 검증까지).** 마이그레이션된 실 스키마에서 Q 코드를 생성해 컴파일 타임 + 스키마 검증까지 주는 가장 강한 안전이지만, 코드젠 파이프라인·빌드 복잡도가 크다. Exposed 의 Kotlin 네이티브 DSL·낮은 셋업 비용을 우선했다(스키마 드리프트는 아래 Testcontainers 로 보완).
- **QueryDSL-JPA.** Hibernate 위의 쿼리 빌더일 뿐이라 persistence context 를 그대로 써 **대량 엔티티 관리 비용 문제를 못 푼다.** Boot 4/jakarta/Kotlin 지원 마찰도 있어 기각.
- **Exposed 없이 실행 시간대 분리로 동시성 회피.** 청크 순차 처리 오버런으로 무보장(ADR-0012). 컬럼-스코프 + 멱등으로 대체.

## Consequences

**+**
- 대량 배치에서 persistence context 오버헤드(메모리 적체·dirty checking) 제거 + **컴파일 타임 쿼리 타입 안전**.
- IO 라운드트립 최소화(batch/IN/다중행 INSERT). IDENTITY 로 인한 Hibernate INSERT 배치 무력화 문제를 JDBC 직접 INSERT 로 회피.
- 도메인·모듈 경계 무손상 — 포트 뒤 어댑터 세부라 원칙 IV·ArchUnit 유지. batch 어댑터에만 국한해 리스크를 좁힌다.

**−**
- `:infra:persistence` 에 **JPA 와 Exposed 두 영속 기술이 공존**한다 — 매핑 모델·학습 곡선이 이중화된다. batch 파이프라인 어댑터로만 국한해 최소화한다.
- **Exposed 테이블 object 가 Flyway 스키마와 드리프트할 수 있다**(Exposed 는 실 스키마를 읽지 않음 — 컴파일 안전 ≠ 스키마 검증). → **MySQL Testcontainers 통합 테스트로 드리프트를 차단**한다(기존 전략 재사용).
- Exposed 트랜잭션·Spring tx 브리지, `rewriteBatchedStatements` 등 **빌드·구성 셋업이 선행**으로 필요하다(P1).

## 후속

- Exposed 빌드 셋업(버전 카탈로그 좌표 · `exposed-spring-boot-starter` · `SpringTransactionManager` 구성) · MySQL JDBC URL `rewriteBatchedStatements=true` 는 P1 에서 처리한다.
- `food` 서빙 읽기까지 Exposed 로 확장할지는 별도 판단이며, 현 결정은 batch 파이프라인 한정이다(필요 시 후속 ADR).

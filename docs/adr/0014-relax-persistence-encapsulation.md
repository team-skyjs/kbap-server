# 0014. 영속 캡슐화 완화 — 엔티티·리포지토리 public, 소비 계층 직접 참조

- **상태**: Accepted
- **날짜**: 2026-07-22
- **관련**: specs/kb-220-remove-internal · Jira KB-220 · supersedes [ADR-0012](./0012-dissolve-persistence-module-and-ports.md)의 internal 캡슐화 결정(영속 코드의 도메인 모듈 소유는 유지) · 헌법 v5.0.0(원칙 III·IV 개정)

## Context

ADR-0012(KB-134)는 영속 코드를 소유 도메인 모듈 안으로 옮기며 엔티티·Spring Data 리포지토리를 Kotlin `internal` 로 감추고, 도메인 서비스 하나를 유일한 public 창구로 두었다. Gradle 모듈이 컴파일 단위라 이 경계는 컴파일러가 강제했다.

운영해 보니 캡슐화의 비용이 드러났다. 배치 콘텐츠 파이프라인(KB-182)처럼 도메인 로직 없이 리포지토리만 필요한 소비 계층은 도메인 모듈에 **위임 전용 창구 서비스**를 만들어야 했다 — `FoodContentBatchService`(배치 전용 조회·저장 위임)·`AvoidanceCatalogService`(단건 위임 메서드 하나). 그 결과:

- 배치 성격의 데이터 접근 코드가 도메인 모듈에 섞였다(모듈 응집 훼손).
- 도메인 하나를 읽는 데 창구 서비스라는 조각이 하나 더 생겼다(ADR-0012 가 조각 수를 줄이려던 방향과 역행).
- 창구는 리포지토리 호출을 그대로 위임할 뿐 도메인 로직이 없어, 캡슐화가 지키는 실질이 없었다.

## Decision

**엔티티·Spring Data 리포지토리의 `internal` 을 제거하고 public 으로 둔다.** 소비 계층(`:application`·`:app:*`)은 단순 영속 접근이 필요하면 리포지토리를 직접 참조하고, 위임 전용 창구 서비스는 만들지 않는다(기존 2개 삭제).

유지되는 것:

- **영속 코드의 소유 도메인 모듈 배치**(ADR-0012 의 핵심) — `:infra:persistence` 로 되돌리지 않는다.
- **도메인 로직의 도메인 서비스 소유** — 검증·상태 전이·정책은 여전히 도메인 서비스가 소유하며, 리포지토리 직접 참조는 비즈니스 로직을 소비 계층으로 옮기는 허가가 아니다.
- **JPA 연관관계 금지**(참조는 id 값)·**Flyway 스키마 owner(=api)**.
- **명시적 트랜잭션 경계** — 소비 계층이 리포지토리를 직접 쓸 때는 필요한 경계를 스스로 선언한다. 배치 진행 저장의 독립 커밋(구 창구의 `@Transactional(REQUIRES_NEW)`)은 배치 프로세서의 `TransactionTemplate(PROPAGATION_REQUIRES_NEW)` 로 이관해 의미를 보존했다.

부수 정리: 리포지토리 `internal` 이 컴파일러 제약(공개 시그니처에 internal 타입 노출 금지)으로 강제하던 도메인 서비스의 `internal constructor` 도 함께 제거했다.

## Alternatives Considered

- **창구 서비스 유지(현상 유지)** — 리포지토리가 필요한 소비 계층이 늘 때마다(사진 KB-184·기피성분 KB-209 등) 위임 메서드가 계속 늘어난다. 기각.
- **배치 전용 창구를 배치 모듈로 이동** — 캡슐화는 지켜지지만 위임 계층 자체는 그대로 남아 얻는 것이 없다. 기각.
- **ArchUnit 으로 "리포지토리 접근은 배치·application 만" 재제한** — 완화하는 결정에서 새 제한을 발명하는 자기모순. 필요가 실증되면 후속으로 검토. 기각.

## Consequences

- 위임 전용 창구 2개 삭제, `internal` 선언 15곳(리포지토리 9 + 생성자 6) 제거 — 도메인 하나를 다루는 조각 수가 줄었다.
- 컴파일러가 막아 주던 "도메인 서비스 우회" 가 가능해졌다 — **도메인 로직을 소비 계층에 쓰는 실수는 이제 리뷰가 잡아야 한다**(헌법 원칙 IV 의 규율 조항). ArchUnit 의 기존 경계 규칙(도메인→상위 금지·`@Entity` 위치·도메인 모델 ORM-free)은 유지된다.
- 창구 테스트는 리포지토리·엔티티 레벨(`FoodJpaRepositoryTest`)과 배치 파이프라인 특성화 테스트(`FoodContentPipelineTest` — 진행 저장 독립 커밋·READY 전이)로 이전돼 시나리오 손실이 없다.
- 헌법 v5.0.0(원칙 IV: Persistence Encapsulation → Persistence Ownership)·`meogo-conventions.md`·CLAUDE.md 가 이 정책을 서술한다.

# 0012. persistence 모듈 해체·리포지토리 port 폐기 — 영속은 도메인 모듈 안에 internal

- **상태**: Accepted
- **날짜**: 2026-07-13
- **관련**: specs/kb-134-architecture-simplification · Jira KB-134(KB-101 흡수) · supersedes [ADR-0006](./0006-central-persistence-adapter-and-decoupled-batch.md)(중앙 영속 어댑터)·[ADR-0008](./0008-modular-monolith-shared-domain.md)의 영속 배치 결정(모듈러 모놀리스·batch 직접 의존은 유지) · 헌법 v3.0.0(원칙 III·IV 재정의)

## Context

클린아키텍처 ports & adapters 구조에서 회원 하나를 다루는 데 다섯 조각이 필요했다 — 도메인 모델(`Member`)·port 인터페이스(`MemberRepository`)·JPA 엔티티(`MemberJpaEntity`)·Spring Data 리포지토리(`MemberJpaRepository`)·어댑터(`MemberRepositoryAdapter`). 조각들은 두 모듈(`:core:member`·`:infra:persistence`)에 흩어졌고, 부트앱이 `runtimeOnly` 로 어댑터를 조립하는 배선이 따라붙었다.

이 구조가 사주는 것은 "영속 기술 교체 가능성"과 "페이크 리포지토리 단위 테스트"다. 그러나 영속 기술 교체는 일어나지 않았고, 경계 강제는 리뷰+ArchUnit 에 의존했다. 반면 비용은 매일 냈다 — 폴더 뎁스, 조각 수, 기능 하나마다 port·어댑터·조립 보일러플레이트, 두 모듈을 오가는 탐색.

Gradle 멀티모듈에서는 모듈이 곧 컴파일 단위라, Kotlin `internal` 만으로 모듈 밖 접근을 **컴파일러가** 차단할 수 있다. port 없이도 경계가 지켜진다.

## Decision

1. **`:infra:persistence` 를 해체**한다. 엔티티·Spring Data 리포지토리를 데이터를 소유하는 도메인 모듈 안으로 옮기고 `internal` 로 감춘다. 어댑터는 폐기한다.
2. **리포지토리 port 를 전부 폐기**한다(`MemberRepository`·`FoodRepository`·`FoodScoringSource`·`AvoidanceSubstanceRepository`·`ScanHistoryRepository`·`RefreshTokenStore` 인터페이스). 외부 시스템 클라이언트 seam(`ScannedNameInterpreter`·`SocialTokenVerifier` 등)은 리포지토리 port 가 아니므로 유지하며, `:infra:llm` 도 그대로 둔다.
3. 각 도메인 모듈은 **도메인 서비스(`MemberService`·`FoodService` 등) 하나를 public 창구**로 둔다. `RefreshTokenStore` 는 `:domain:member` 의 Redis 구체 클래스로 이름을 승계한다(회원 컨텍스트 소유 저장소 — member 의 두 번째 공개 창구).
4. **도메인 모델과 JPA 엔티티는 분리 유지**한다. 같은 모듈 안에 있어도 도메인 모델은 ORM 애너테이션을 갖지 않고, 변환(`toDomain`/`from`)은 엔티티가 책임진다.
5. **JPA 연관관계를 전면 금지**한다. 참조는 id 값 클래스(`FoodId`·`MemberId`, `:core` 소유 + `AttributeConverter`)로만 들고, 연관 데이터는 도메인 서비스가 id(목록)로 명시 조회한다. 외래키 제약은 Flyway 스키마가 강제한다(ON DELETE 정책 없음 — 소프트 삭제 구조).
6. **모듈 명칭을 개편**한다 — `core/` 컨테이너 → `domain/`(`:domain:food` 등, 패키지 `com.meogo.domain.<d>`), `:core:kernel` → `:core`(패키지 `com.meogo.core`). 전 도메인이 공유하는 영속 공통(`BaseEntity`·`EntityStatus`)과 id 값 클래스·컨버터는 `:core` 로 옮긴다(jakarta.persistence·hibernate 애너테이션은 `compileOnly`).
7. `:application:client` 는 도메인 서비스를 조합해 유스케이스를 만든다. 트랜잭션 경계·"컨텍스트 간 조합은 application 에서만" 규약은 유지한다. 부트앱의 `runtimeOnly` 어댑터 조립은 소멸한다.
8. 페이크 port 유스케이스 단위 테스트는 **통합 테스트로 흡수**한다 — 도메인 서비스 통합 테스트(MySQL Testcontainers)와 app:api MockMvc 컨트롤러 테스트가 시나리오를 승계한다(mockk 미도입).
9. 죽은 MongoDB 잔재(yml·docker-compose·카탈로그)를 제거한다.

## Alternatives Considered

- **현행 유지 + 부분 단순화**: port·어댑터·별도 모듈이라는 비용 구조가 그대로 남아 목적 미달. 기각.
- **도메인 서비스 인터페이스화(페이크 테스트 유지)**: port 를 한 층 위에서 재생산하는 셈 — 보일러플레이트 제거 취지와 상충. 기각.
- **mockk 도입으로 유스케이스 단위 테스트 유지**: 의존성 추가 대비, 유스케이스가 얇아져(서비스 조합) 통합 층 검증이 실질 커버리지와 일치. 기각(사용자 결정).
- **참조 id 를 Long 유지**: 값 클래스+컨버터 보일러플레이트는 base 1개 + 타입당 한 줄로 수렴하고, id 혼동을 컴파일 단계에서 차단하는 이득이 크다고 판단. 기각(사용자 결정 — 값 클래스 채택).
- **도메인 모델·엔티티 통합**: 도메인 불변·영속 무결성 규칙이 한 클래스에 섞인다. 기각(이슈 명시 배제).

## Consequences

- **좋은 점**: 도메인 하나 = 한 모듈(조각 5→3), 경계 강제가 컴파일러로 상향, N+1·LazyInitializationException 구조적 불가, 조립 배선 소멸, 후속 리뷰 기능(KB-128·129·131)은 영속 코드를 한 번만 작성.
- **트레이드오프**: 도메인 모듈이 Spring·JPA 를 갖게 된다(순수 도메인 모듈 포기). 유스케이스 검증이 통합 테스트로 옮겨져 테스트가 느려진다. 영속 기술 교체 시 도메인 모듈을 직접 수정해야 한다(현실적으로 일어나지 않는 교체에 대한 보험 해지).
- **후속**: 헌법 v3.0.0 반영 완료, ArchUnit `ModuleBoundaryTest` 전면 재작성(연관관계 금지·@Entity 위치·도메인 모델 ORM-free 포함), CLAUDE.md·docs/architecture 갱신, Jira 리뷰 태스크 본문을 새 구조 기준으로 갱신.

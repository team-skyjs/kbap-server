# Research: internal 제거 — 설계 결정

Technical Context 에 NEEDS CLARIFICATION 은 없다. 아래는 구현 방향을 고정하는 결정 6건이다.

## D1. 배치 진행 저장의 트랜잭션 소유 — TransactionTemplate(REQUIRES_NEW)

- **Decision**: `FoodContentItemProcessor` 가 `PlatformTransactionManager` 를 주입받아 `TransactionTemplate(propagation = REQUIRES_NEW)` 로 진행 저장(`foodRepository.save`)을 감싼다. 리더 조회(`findIncompleteAfter`)는 Spring Data 기본 읽기 트랜잭션에 맡기고, 라이터의 READY 전이(`food.transitionToReadyIfComplete()` + `save`)는 Spring Batch 청크 트랜잭션에 참여시킨다.
- **Rationale**: 현재 `FoodContentBatchService.saveProgress` 의 `@Transactional(REQUIRES_NEW)` 는 "뒤 작업이 실패해도 앞 작업 결과는 커밋 유지(재실행 시 실패 작업만 재시도)" 라는 파이프라인 핵심 의미다. 창구를 지우고 프로세서 안에서 `repo.save()` 만 호출하면 청크 트랜잭션에 **참여(join)** 해 청크 실패 시 진행분까지 롤백된다 — 의미 파괴. 프록시 `@Transactional` 은 `@Bean` 으로 조립되는 배치 컴포넌트에 얹기 어색하므로 프로그래매틱 경계(TransactionTemplate)가 정직하다. `completeContent` 는 현재도 REQUIRED 라 청크 트랜잭션에 join 하고 있었으므로 라이터 인라인은 의미 동일.
- **Alternatives considered**: (a) 배치용 `@Transactional` 헬퍼 빈 신설 — 창구 서비스를 배치 모듈로 옮겨 적을 뿐, 없애려던 위임 계층의 재생산. (b) 청크 트랜잭션에 그냥 참여 — 진행 커밋 유지 의미 상실(KB-220 이 보존을 요구). 기각.

## D2. internal constructor 8곳 함께 제거

- **Decision**: 도메인 서비스 8개(`AvoidanceCatalogService` 는 삭제되므로 실제 7개 + food 의 `FoodContentBatchService` 삭제)의 `internal constructor` 를 모두 일반 public 생성자로 되돌린다.
- **Rationale**: Kotlin 은 public 멤버 시그니처에 internal 타입 노출을 금지하므로, 리포지토리가 internal 인 동안 생성자도 internal 이어야 했다 — 순수한 컴파일러 부산물. 리포지토리가 public 이 되면 존재 이유가 소멸하고, 남겨두면 "왜 이것만 internal 인가"라는 오독을 만든다.
- **Alternatives considered**: 유지 — 외부 수동 생성 방지 효과가 있으나 Spring 조립 프로젝트에서 실익 없음(스펙 Assumptions 에 범위 포함 근거 명시). 기각.

## D3. 빈 컬렉션 가드는 호출부(HomeApplicationService)로 이동

- **Decision**: `AvoidanceCatalogService.getSubstancesByCodes` 의 `if (codes.isEmpty()) emptyList()` 가드는 `HomeApplicationService` 가 소유한다(`avoidedCodes` 가 비어 있으면 리포지토리 호출 생략).
- **Rationale**: 빈 IN 절 처리를 Hibernate 버전 동작에 암묵 의존시키지 않고, null/빈 분기는 호출부가 소유한다는 기존 컨벤션과 일치. 위임 서비스에 남기려면 서비스를 남겨야 하는데 그게 제거 대상이다.
- **Alternatives considered**: 리포지토리 default 메서드로 가드 — 창구를 리포지토리 안으로 옮긴 것과 같아 소득 없음. 기각.

## D4. ArchUnit — 규칙 구조 변경 없음(점검·문구만)

- **Decision**: `ModuleBoundaryTest` 의 기존 규칙(core Spring-free · 도메인→상위 계층 금지 · 도메인 모델 ORM-free · `@Entity` 는 도메인 모듈에만 · 컨트롤러 `/api/v`)은 전부 새 정책에서도 유효하므로 유지한다. 옛 정책("영속 접근은 …도메인 서비스만 허용")을 서술한 then 설명 문구만 갱신한다. 신규 규칙은 추가하지 않는다.
- **Rationale**: 리포지토리 캡슐화는 ArchUnit 이 아니라 Kotlin `internal`(컴파일러)이 전담했으므로 제거할 규칙이 없다. "도메인 로직은 도메인 서비스 소유"는 기계 검증 불가능한 판단 규칙이라 ArchUnit 대상이 아니다.
- **Alternatives considered**: "리포지토리 직접 사용은 배치·application 만" 같은 신규 제한 규칙 — 완화하는 기능에서 새 제한을 발명하는 자기모순. 기각(필요해지면 후속).

## D5. 문서 개정 — 헌법 5.0.0 MAJOR + ADR-0014

- **Decision**: 헌법 4.0.0 → **5.0.0**(MAJOR — 원칙 IV 비호환 재정의 + 원칙 III "유일 창구" 조항 완화). Sync Impact Report 에 KB-220 근거 기록. ADR-0014 신설(ADR-0012 의 internal 캡슐화 부분을 supersede). `meogo-conventions.md`(18·28·58·69·96행 일대)·`meogo-api-module-structure.md`(79행 일대)·CLAUDE.md(개요·모듈 구조·JPA 엔티티 작성 절의 internal 서술) 갱신. 원칙 IV 재작성 시 현실과 어긋난 "도메인 모델·엔티티 분리(toDomain/from)" 서술도 현행 코드(엔티티=도메인 모델)로 정합화한다.
- **Rationale**: DoD 명시 항목. 문서가 옛 정책을 서술하면 다음 기여자가 창구 서비스를 재생산한다(스펙 US3).
- **Alternatives considered**: ADR-0012 본문 수정 — ADR 은 불변 기록이므로 신설+supersede 가 관례. 기각.

## D6. 창구 테스트 시나리오 이전

- **Decision**: `FoodContentBatchServiceTest`(134행, 시나리오 5개)를 `FoodJpaRepositoryTest` 로 개명·이전한다 — `getIncompleteFoods` 3건은 `findIncompleteAfter` 리포지토리 직접 검증으로, `completeContent` 2건은 "엔티티 `transitionToReadyIfComplete` + `save`" 조합 검증으로 다시 쓴다. 배치 쪽 신규 동작(프로세서의 REQUIRES_NEW 진행 저장)은 `:app:batch` 통합 테스트로 실패-선행 작성한다(헌법 I).
- **Rationale**: 검증하던 동작(키셋 페이징·READY 제외·전이 조건)은 서비스가 아니라 쿼리·엔티티의 성질이므로 리포지토리·엔티티 레벨로 내리는 게 자연스럽고, 시나리오 손실이 없다(스펙 Edge Case).
- **Alternatives considered**: 테스트 삭제 — 시나리오 손실, 헌법 I 위반. 기각.

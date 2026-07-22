# Feature Specification: 모든 JPA 엔티티·리포지토리 internal 제거 — 영속 캡슐화 완화

**Feature Branch**: `kb-220-remove-internal`

**Created**: 2026-07-22

**Status**: Draft

**Input**: Jira [KB-220](https://simhani1.atlassian.net/browse/KB-220) — "[BE] 모든 JPA 엔티티·리포지토리 internal 제거 — 영속 캡슐화 완화"

## User Scenarios & Testing *(mandatory)*

이 기능의 사용자는 서비스 최종 사용자가 아니라 **kbap-server 를 개발·유지보수하는 개발자**다. 가치는 "리포지토리가 필요한 소비 계층이 우회용 창구 서비스 없이 리포지토리를 직접 쓰게 되어, 도메인 모듈에 섞인 소비 계층 성격의 코드가 사라지는 것"이다.

### User Story 1 - 소비 계층이 리포지토리를 직접 참조할 수 있다 (Priority: P1)

개발자가 배치·애플리케이션 계층에서 도메인 데이터 접근이 필요할 때, 도메인 모듈에 창구 서비스를 새로 만들지 않고 해당 도메인의 Spring Data 리포지토리를 직접 주입받아 사용한다.

**Why this priority**: 나머지 모든 작업(창구 제거·문서 갱신)의 전제 조건이다. `internal` 이 남아 있는 한 소비 계층의 직접 참조는 컴파일이 막힌다.

**Independent Test**: 도메인 외부 모듈(예: `:app:batch`)의 코드가 도메인 리포지토리를 import·주입해 컴파일·동작하는 것으로 검증한다.

**Acceptance Scenarios**:

1. **Given** 모든 도메인 모듈의 영속 타입에서 `internal` 이 제거된 상태, **When** 도메인 외부 모듈이 리포지토리·엔티티를 참조하면, **Then** 컴파일이 성공한다.
2. **Given** 전체 코드베이스, **When** 도메인 모듈의 리포지토리·엔티티 선언을 검사하면, **Then** `internal` 가시성 선언이 하나도 남아 있지 않다.

---

### User Story 2 - 우회용 창구 서비스가 사라진다 (Priority: P2)

개발자가 배치·애플리케이션 코드를 읽을 때, 리포지토리 호출을 그대로 위임만 하는 중간 서비스 없이 데이터 접근 흐름을 한 단계로 파악한다.

**Why this priority**: `internal` 제거의 실질 효익이 실현되는 지점이다. 창구가 남아 있으면 캡슐화 완화는 이름뿐인 변경이 된다.

**Independent Test**: 위임 전용 창구 서비스(`FoodContentBatchService`·`AvoidanceCatalogService`)가 삭제되고, 기존 소비처가 리포지토리를 직접 사용하면서 전체 테스트가 그린인 것으로 검증한다.

**Acceptance Scenarios**:

1. **Given** 콘텐츠 수집 배치 파이프라인, **When** 창구 서비스를 제거하고 배치가 food 리포지토리를 직접 사용하도록 배선하면, **Then** 기존 배치 동작(불완전 음식 조회·진행 저장·READY 전이)과 트랜잭션 의미(작업별 커밋 유지)가 그대로 보존된다.
2. **Given** 홈 화면 유스케이스, **When** 창구 서비스를 제거하고 애플리케이션 계층이 avoidance 리포지토리를 직접 사용하도록 배선하면, **Then** 기존 응답이 동일하게 유지된다.
3. **Given** 도메인 로직(검증·상태 전이·정책)을 포함한 도메인 서비스, **When** 창구 제거 범위를 판단하면, **Then** 위임 전용이 아닌 서비스는 제거 대상이 아니다.

---

### User Story 3 - 아키텍처 규칙과 문서가 새 정책을 서술한다 (Priority: P3)

개발자가 헌법·컨벤션 문서·아키텍처 테스트를 볼 때, "리포지토리는 internal, 유일 창구는 도메인 서비스"라는 옛 정책 대신 완화된 새 정책을 일관되게 확인한다.

**Why this priority**: 코드와 문서가 어긋나면 다음 기여자가 옛 정책을 따라 창구 서비스를 다시 만든다. 코드 변경이 끝난 뒤에만 의미 있는 마무리 작업이다.

**Independent Test**: 헌법 원칙 IV·컨벤션 문서·CLAUDE.md·ArchUnit 스펙에서 영속 캡슐화 서술을 검색해 옛 정책 문구가 남아 있지 않은 것으로 검증한다.

**Acceptance Scenarios**:

1. **Given** 헌법 원칙 IV(Persistence Encapsulation), **When** 개정하면, **Then** "internal 로 감춘다·컴파일러가 강제한다" 서술이 완화된 정책으로 대체되고 개정 이력이 남는다.
2. **Given** ArchUnit `ModuleBoundaryTest`, **When** 영속 캡슐화 관련 규칙을 점검하면, **Then** 옛 정책을 전제한 규칙은 갱신·제거되고 유지되는 규칙(도메인→상위 계층 금지, 도메인 모델 ORM-free, `@Entity` 위치, 컨트롤러 경로)은 전부 통과한다.
3. **Given** CLAUDE.md·컨벤션 문서, **When** "리포지토리는 internal" 류 서술을 검색하면, **Then** 새 정책 서술로 갱신되어 있다.

### Edge Cases

- 창구 서비스가 단순 위임 외에 **트랜잭션 경계를 소유**하는 경우(예: 진행 저장의 독립 커밋 — 뒤 작업이 실패해도 앞 작업 결과는 유지): 창구를 제거해도 그 트랜잭션 의미는 새 소비처에서 동일하게 보존되어야 한다.
- 도메인 서비스 생성자의 `internal constructor` 는 리포지토리가 `internal` 타입이라 컴파일러가 요구했던 부산물이다: 리포지토리가 public 이 되면 존재 이유가 사라지므로 함께 제거한다.
- JPA 엔티티는 현재 이미 public 이다(DoD 의 "엔티티 internal 제거"는 확인 항목): 전수 검사로 잔존 `internal` 이 없음을 확인하는 것으로 충족한다.
- 배치 앱은 컴포넌트 스캔을 좁혀 두고 필요한 빈만 `@Import` 로 조립한다: 창구 서비스 제거 후에도 리포지토리 빈이 배치 컨텍스트에서 정상 조립되어야 한다.
- 창구 서비스를 참조하던 기존 테스트는 새 배선에 맞게 이동·재작성되며, 검증하던 동작(시나리오)은 잃지 않는다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 모든 도메인 모듈의 Spring Data 리포지토리(커스텀 리포지토리 인터페이스·구현 포함)에서 `internal` 가시성을 제거해 도메인 외부 모듈이 직접 참조할 수 있어야 한다.
- **FR-002**: 모든 도메인 모듈의 JPA 엔티티에 `internal` 가시성이 남아 있지 않음을 전수 확인해야 한다.
- **FR-003**: 리포지토리 `internal` 의 부산물인 도메인 서비스의 `internal constructor` 를 제거해야 한다.
- **FR-004**: 도메인 로직 없이 리포지토리 위임만 하는 우회용 창구 서비스(`FoodContentBatchService`·`AvoidanceCatalogService`)를 제거하고, 기존 소비처(배치·애플리케이션 계층)가 리포지토리를 직접 사용하도록 배선해야 한다.
- **FR-005**: 창구 제거 과정에서 기존 동작과 트랜잭션 의미(읽기 전용 조회, 작업별 독립 커밋, 상태 전이 커밋)를 동일하게 보존해야 한다.
- **FR-006**: ArchUnit `ModuleBoundaryTest` 에서 옛 영속 캡슐화 정책을 전제한 규칙을 갱신·제거하고, 나머지 경계 규칙은 새 구조에서 전부 통과해야 한다.
- **FR-007**: 헌법 원칙 IV(Persistence Encapsulation)·컨벤션 문서·CLAUDE.md 의 영속 캡슐화 서술을 새 정책으로 갱신하고, 정책 변경 근거를 ADR 로 기록해야 한다.
- **FR-008**: 전체 빌드·테스트가 그린이어야 한다.

### Key Entities

- **Spring Data 리포지토리**: 각 도메인의 영속 접근 창구. 현재 `internal` 로 감춰진 9개 선언(avoidance·bookmark·member·scan·image·food ×3·metering)이 public 으로 바뀐다.
- **우회용 창구 서비스**: `internal` 리포지토리를 소비 계층에 중계하기 위해서만 존재하는 위임 전용 도메인 서비스. 제거 대상.
- **도메인 서비스(유지)**: 도메인 로직(검증·상태 전이·정책·유비쿼터스 언어 행위)을 소유한 서비스. 이번 변경의 대상이 아니며 계속 비즈니스 로직의 소유자다.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 도메인 모듈의 리포지토리·엔티티에 남은 `internal` 선언 수가 0 이다.
- **SC-002**: 위임 전용 창구 서비스 수가 0 이다 (기존 2개 제거).
- **SC-003**: 전체 빌드·테스트(아키텍처 테스트 포함)가 그린이다.
- **SC-004**: 헌법·컨벤션 문서·CLAUDE.md 에서 "리포지토리 internal·도메인 서비스 유일 창구" 류의 옛 정책 서술 검색 결과가 0 건이다.
- **SC-005**: 배치 콘텐츠 수집 파이프라인과 홈 API 의 기존 동작이 변경 전과 동일하다 (기존 테스트 시나리오 전부 유지·통과).

## Assumptions

- **엔티티는 이미 public** — DoD 의 "엔티티 internal 제거" 항목은 실제 수정이 아니라 전수 확인으로 충족된다 (2026-07-22 코드 기준 `internal` 은 리포지토리 계열 9개 선언에만 존재).
- **`internal constructor` 제거를 범위에 포함** — Jira DoD 에 명시돼 있지 않으나, 리포지토리 `internal` 때문에 컴파일러가 강제했던 부산물이므로 이번 완화와 한 몸으로 본다.
- **창구 제거 판단 기준은 "도메인 로직 유무"** — 리포지토리 위임 외 로직이 없는 서비스만 제거한다. 현재 해당: `FoodContentBatchService`(food)·`AvoidanceCatalogService`(avoidance). 도메인 로직을 가진 서비스(FoodService·MemberService 등)는 유지한다.
- **도메인 서비스 자체의 존재와 역할은 불변** — 이번 변경은 "리포지토리 접근을 도메인 서비스로만 강제"하는 정책의 완화이지, 도메인 서비스에서 비즈니스 로직을 걷어내는 작업이 아니다.
- **헌법 개정 절차 준수** — 원칙 IV 개정은 헌법 문서의 버전·개정 이력 규칙(Sync Impact Report)을 따른다.

# Feature Specification: 테스트 Spring 컨텍스트 통합 (합성 애너테이션·페이크 통일·TestApp 단일화)

**Feature Branch**: `kb-392-test-context-consolidation`

**Created**: 2026-08-29

**Status**: Draft

**Jira**: [KB-392](https://simhani1.atlassian.net/browse/KB-392) (에픽 KB-100 리팩토링) — 후속 후보 KB-391(컨테이너 JVM 당 1개 공유)

**Input**: User description: "테스트 Spring 컨텍스트 통합 — 합성 애너테이션·페이크 통일·TestApp 단일화로 JVM 당 컨텍스트 수 축소"

## 배경

테스트 프레임워크는 테스트 클래스의 **설정 조합**(부트 클래스·추가 프로퍼티·가져오는 픽스처 조합·MockMvc 유무)이 한 글자라도 다르면 애플리케이션 컨텍스트를 새로 만들고, 만든 컨텍스트는 닫지 않고 캐시에 살려 둔다. 컨텍스트마다 MySQL 컨테이너가 하나 뜨고 마이그레이션 49개가 다시 돈다.

현재 조합은 전부 우연의 산물이다:

- **api**(통합 테스트 76개 → 컨텍스트 8종): MockMvc 유무(44 vs 21) · 같은 소셜 인증 포트의 페이크가 둘(시나리오용 stateless 4클래스 / 프로그래머블 3클래스) · 장소 검색 페이크를 한 클래스만 가져옴 · 클래스 하나짜리 프로퍼티 변형 2개(Hibernate 통계, 구조화 로깅 ecs)
- **common**(11개 → 7종): 도메인별 부트 클래스 `*TestApp` 7개, 스캔 범위도 제각각
- **batch**(6개 → 3종): 느린 잡 픽스처·MockMvc 유무

이 기능은 조합을 **의도적으로 하나**로 모아 JVM 당 컨텍스트를 api 8→2, common 7→1, batch 3→1 로 줄인다. 이것으로 부족하면 컨테이너 자체를 JVM 당 1개로 공유하는 KB-391 을 이어서 진행한다.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - api 통합 테스트를 한 가지 설정으로 통일 (Priority: P1)

개발자가 api 통합 테스트를 쓸 때 3줄짜리 애너테이션 조합을 고민하지 않고 **합성 애너테이션 하나**(`@IntegrationTest`)만 붙인다. 기존 76개 클래스도 이 한 줄로 바뀌고, 소셜 인증·장소 검색 페이크는 모든 통합 테스트에 기본 포함된다. 그 결과 api 테스트 JVM 의 컨텍스트가 8종 → 2종(기본 + 구조화 로깅 변형)이 된다.

**Why this priority**: 컨텍스트 76개 중 8종이 가장 큰 낭비이고, 이후 새 테스트가 조합을 늘리는 것을 구조적으로 막는다.

**Independent Test**: `./gradlew :api:test` 실행 중 `docker ps --filter ancestor=mysql:8.4 | wc -l` 이 최대 2(현재 8) 이고 전부 그린. 테스트 로그의 애플리케이션 기동 메시지가 2회.

**Acceptance Scenarios**:

1. **Given** api 통합 테스트 76개, **When** 헤더를 `@IntegrationTest` 한 줄로 바꾸면, **Then** MockMvc·MySQL·Redis·페이크가 전부 주입되고 모든 테스트가 통과한다.
2. **Given** 시나리오 테스트(로그인→스캔→주문 여정, 4개)와 인증 컨트롤러 테스트(실패 주입·탈퇴 삭제 기록, 3개), **When** 통일된 페이크 하나를 공유하면, **Then** 두 부류 모두 기존 단언을 그대로 통과한다 — 여정별로 다른 토큰이 다른 회원이 되고, 실패 주입·삭제 기록·초기화가 동작한다.
3. **Given** Hibernate 통계로 N+1 을 단언하는 테스트, **When** 통계 설정을 테스트 전역 설정으로 옮기면, **Then** 별도 컨텍스트 없이 같은 단언이 통과한다.
4. **Given** 구조화 로깅(ecs) 테스트, **When** 콘솔 출력 형식 전체를 바꾸는 설정이라 분리가 불가피하면, **Then** 이 하나만 별도 컨텍스트로 남기고 그 이유를 기록한다.
5. **Given** 통합 후 한 데이터베이스를 공유하는 클래스가 44 → 75 로 늘어난 상태, **When** 전체 테스트를 2회(또는 순서 셔플) 실행하면, **Then** 결과가 동일하게 그린이다. 순서 의존이 드러난 클래스는 자체 정리 코드로 고친다(다른 클래스에 정리 책임을 떠넘기지 않는다).

---

### User Story 2 - common 모듈 부트 클래스 단일화 (Priority: P2)

common 의 리포지토리 테스트 11개가 도메인별 `*TestApp` 7개 대신 **`CommonTestApp` 하나**를 쓴다. 컨텍스트 7 → 1.

**Why this priority**: api 다음으로 큰 낭비(7 컨테이너)이며 변경이 기계적이다.

**Independent Test**: `./gradlew :common:test` 실행 중 MySQL 컨테이너 1개, 전부 그린.

**Acceptance Scenarios**:

1. **Given** 도메인별 `*TestApp` 7개, **When** `CommonTestApp` 하나로 대체하면, **Then** 리포지토리 테스트 11개가 전부 통과하고 `*TestApp` 7개 파일은 삭제된다.
2. **Given** common 에 있는 외부 시스템 어댑터(LLM 구성 등), **When** `CommonTestApp` 이 도메인 패키지만 스캔하면, **Then** 외부 키 없이도 컨텍스트가 뜬다.

---

### User Story 3 - batch 모듈 설정 통일 (Priority: P3)

batch 테스트 6개가 같은 설정(느린 잡 픽스처 + MockMvc 포함)을 써 컨텍스트 3 → 1.

**Why this priority**: 규모가 작다(3 컨테이너).

**Independent Test**: `./gradlew :batch:test` 실행 중 MySQL 컨테이너 1개, 전부 그린.

**Acceptance Scenarios**:

1. **Given** batch 테스트 6개, **When** 하나의 합성 애너테이션(또는 동일 헤더)으로 통일하면, **Then** 잡 트리거 테스트의 느린 잡이 다른 테스트에 영향을 주지 않고 전부 통과한다.

---

### User Story 4 - 규칙을 문서에 남긴다 (Priority: P4)

다음 개발자가 "통합 테스트는 `@IntegrationTest` 하나, `@Import`·`properties` 로 조합을 새로 만들지 않는다(컨텍스트 캐시 키)" 를 프로젝트 지침과 위키에서 바로 안다.

**Why this priority**: 규칙 없이는 조합이 다시 늘어난다.

**Independent Test**: `CLAUDE.md` 테스트 절과 `../kbap-agenthub` 위키에 규칙과 근거가 있다.

**Acceptance Scenarios**:

1. **Given** 프로젝트 지침, **When** 테스트 절을 읽으면, **Then** 합성 애너테이션 사용 규칙, 조합을 늘리지 않는 이유(컨텍스트 = 컨테이너 + 마이그레이션), 예외(구조화 로깅) 가 적혀 있다.

---

### Edge Cases

- 통합된 페이크가 모든 컨텍스트에 있으므로, 실제 소셜 인증·장소 검색 구현이 테스트에서 우연히 호출되는 일은 사라진다(이전에도 페이크 없는 컨텍스트에서는 실 구현이 빈으로 떠 있었으나 호출되지 않았음).
- 프로그래머블 페이크의 상태(실패 주입·삭제 기록)는 클래스 간에 새어 나갈 수 있다 — 이를 쓰는 테스트는 `beforeSpec`/`afterSpec` 에서 초기화한다(현재도 그렇게 하고 있음).
- 한 DB 를 공유하는 클래스가 늘어 고정 id 시드·유일 이름 충돌 가능성이 커진다 — 2회 실행으로 드러내고, 드러난 클래스에만 정리 코드를 넣는다.
- 새 테스트가 `@IntegrationTest` 대신 `@SpringBootTest` 를 직접 쓰면 조합이 다시 늘어난다 — 규칙(US4)으로 막고, 강제는 리뷰로 한다(ArchUnit 검사 추가는 범위 밖).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: common 테스트 픽스처에 합성 애너테이션 `@IntegrationTest` 를 두어, 하나로 `@SpringBootTest` + MockMvc 자동 구성 + MySQL·Redis 픽스처 + 소셜 인증 페이크 + 장소 검색 페이크를 모두 가져와야 한다.
- **FR-002**: api 통합 테스트 76개 전부가 `@IntegrationTest` 만 쓰고, 클래스별 `@SpringBootTest`·`@AutoConfigureMockMvc`·`@Import` 를 갖지 않아야 한다(구조화 로깅 테스트 1개 예외).
- **FR-003**: 소셜 인증 페이크는 하나여야 한다 — 기본 동작은 토큰 값을 그대로 사용자 식별자로 삼고(여정마다 다른 회원), 실패 주입·계정 삭제 기록·초기화를 지원한다. 시나리오 전용 페이크는 삭제한다.
- **FR-004**: Hibernate 통계 설정은 api 테스트 전역 설정으로 옮기고, 그것을 위한 클래스별 프로퍼티 변형은 없애야 한다.
- **FR-005**: common 의 `*TestApp` 7개를 `CommonTestApp` 하나로 대체하고, 도메인 패키지(엔티티·리포지토리)만 스캔해 외부 어댑터 구성이 뜨지 않아야 한다.
- **FR-006**: batch 테스트 6개는 동일한 설정 조합을 써 컨텍스트가 1개여야 한다.
- **FR-007**: JVM 당 컨텍스트 수가 api ≤ 2, common = 1, batch = 1 이어야 한다(실행 중 MySQL 컨테이너 수로 측정).
- **FR-008**: 전체 빌드가 2회 연속(또는 순서 셔플) 그린이어야 한다.
- **FR-009**: 테스트 클래스의 단언·시나리오 본문은 바꾸지 않는다 — 헤더 치환, 페이크 참조 교체, 순서 의존이 드러난 클래스의 정리 코드 추가만 허용한다.
- **FR-010**: `CLAUDE.md` 테스트 절과 지식 위키에 규칙("통합 테스트는 `@IntegrationTest` 하나, 조합을 늘리지 않는다")과 근거·예외를 기록해야 한다.

### Key Entities

- **합성 애너테이션 `@IntegrationTest`**: api 통합 테스트의 유일한 헤더. 설정 조합의 단일 출처.
- **통일 소셜 인증 페이크**: 하나의 프로그래머블 구현(검증기 + 계정 삭제기). 기본 = 토큰을 식별자로, 선택 = 실패 주입·삭제 기록.
- **`CommonTestApp`**: common 리포지토리 테스트의 유일한 부트 클래스.
- **컨텍스트 캐시 키**: 부트 클래스·프로퍼티·Import 조합·MockMvc 유무. 다르면 새 컨텍스트.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 테스트 실행 중 동시 MySQL 컨테이너 수가 api 2 · common 1 · batch 1 이하(현재 8 · 7 · 3).
- **SC-002**: Flyway 마이그레이션 실행 횟수가 api JVM 당 8 → 2.
- **SC-003**: `./gradlew build` 2회 연속 그린.
- **SC-004**: 테스트 본문(단언·시나리오) diff 0줄 — 변경은 헤더·페이크 참조·정리 코드뿐.
- **SC-005**: 전체 빌드 시간·최대 메모리가 눈에 띄게 감소(컨테이너 18 → 4).

## Assumptions

- 페이크가 기본 포함되어도 그 포트를 호출하지 않는 테스트에는 영향이 없다(빈 교체만).
- 느린 잡 픽스처는 잡 목록에 항목을 하나 더할 뿐이며 잡 수를 단언하는 테스트는 없다(확인됨).
- 구조화 로깅(ecs) 테스트는 콘솔 출력 형식 전체를 바꾸므로 통합하지 않는다 — 1개 컨텍스트를 감수.
- 컨텍스트 = 빈 DB 라는 격리 단위는 유지하되 공유 클래스 수가 늘어난다. 이미 44개가 한 DB 를 공유해 왔으므로 같은 규율(유일 시드·자체 정리)로 대응한다.
- 범위 밖: 컨테이너 자체 공유(KB-391), `@SpringBootTest` 직접 사용을 막는 ArchUnit 검사, 테스트 병렬 실행.

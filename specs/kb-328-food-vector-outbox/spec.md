# Feature Specification: READY 전이 벡터 아웃박스 기반 음식 벡터 동기화

**Feature Branch**: `kb-328-food-vector-outbox`

**Created**: 2026-08-12

**Status**: Draft

**Input**: User description: "KB-328 — READY 전이 벡터 아웃박스 기반 DocumentDB 음식 벡터 동기화. 관리자 승인(PENDING_REVIEW → READY) 시 벡터 아웃박스를 생성하고, 동기화 배치가 임베딩 후 벡터 저장소에 적재한다. embeddingHash 로 멱등 처리, READY 이후 변경·삭제도 동기화, 기존 READY 음식은 백필."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 승인된 음식이 벡터 검색 후보에 반영된다 (Priority: P1)

관리자가 음식 콘텐츠를 검수해 승인(PENDING_REVIEW → READY)하면, 그 음식이 벡터 검색(스캔의 유사 음식 매칭) 후보에 자동으로 반영된다. 승인 시점에 동기화 필요성이 기록(벡터 아웃박스 생성)되고, 동기화 배치가 이를 읽어 처리 시점의 최신 음식 데이터(이름 + 긴 설명)를 임베딩한 뒤 벡터 저장소에 음식 단위로 upsert 한다.

**Why this priority**: 이 흐름이 없으면 관리자가 승인한 음식이 벡터 검색에 영원히 반영되지 않는다 — 기능의 존재 이유이며 이것만 있어도 신규 승인 건부터는 검색 후보가 늘어난다.

**Independent Test**: 음식 하나를 READY 로 승인 → 동기화 배치 1회 실행 → 벡터 저장소에 해당 음식 문서(임베딩·메타데이터 포함)가 생기고 아웃박스가 완료 상태가 되는 것으로 단독 검증 가능.

**Acceptance Scenarios**:

1. **Given** PENDING_REVIEW 상태의 음식, **When** 관리자가 승인하면, **Then** 승인과 같은 트랜잭션에서 그 음식의 UPSERT 아웃박스가 PENDING 으로 생성된다.
2. **Given** PENDING 상태의 UPSERT 아웃박스, **When** 동기화 배치가 실행되면, **Then** 처리 시점의 최신 음식 데이터로 임베딩이 생성되고 벡터 저장소에 음식 단위로 upsert 된 뒤 아웃박스가 COMPLETE 가 된다.
3. **Given** 이미 동일한 내용으로 적재된 음식(embeddingHash 동일), **When** 그 음식의 아웃박스가 다시 처리되면, **Then** 임베딩 호출 없이 아웃박스만 COMPLETE 처리된다.
4. **Given** 임베딩 또는 저장소 반영이 실패한 아웃박스, **When** 배치가 실패를 기록하면, **Then** 시도 횟수가 증가하고 PENDING 으로 남아 다음 실행에서 재시도된다. 최대 횟수를 넘으면 FAILED 로 전환된다.

---

### User Story 2 - READY 이후의 변경·삭제도 벡터 저장소에 반영된다 (Priority: P2)

READY 음식의 이름·설명·이미지가 바뀌거나, 음식이 삭제되거나 READY 가 해제되면 벡터 저장소도 따라간다. 변경은 UPSERT, 삭제·READY 해제는 DELETE 아웃박스로 기록되어 다음 배치에서 반영된다.

**Why this priority**: 최초 승인만 처리하면 벡터 저장소가 시간이 지날수록 실제 데이터와 어긋난다 — 삭제된 음식이 검색 후보로 남는 것이 대표적 사고 경로다.

**Independent Test**: READY 음식의 설명을 수정 → 배치 실행 → 저장소 문서가 새 내용으로 갱신됨. 음식 삭제 → 배치 실행 → 저장소 문서가 제거됨. 각각 단독 검증 가능.

**Acceptance Scenarios**:

1. **Given** READY 음식, **When** 이름·설명이 변경되면, **Then** UPSERT 아웃박스가 생성되고 다음 배치에서 재임베딩 후 문서가 덮어써진다.
2. **Given** READY 음식, **When** 이미지만 변경되면(임베딩 대상 텍스트 불변), **Then** UPSERT 아웃박스가 생성되되 배치는 임베딩을 재호출하지 않고 메타데이터만 갱신한다.
3. **Given** READY 음식, **When** 삭제되거나 READY 가 해제되면, **Then** DELETE 아웃박스가 생성되고 다음 배치에서 벡터 저장소 문서가 제거된다.

---

### User Story 3 - 기존 READY 음식 백필 (Priority: P3)

기능 도입 시점에 이미 READY 인 음식들도 일회성 백필로 UPSERT 아웃박스를 만들어 전량 벡터 저장소에 적재한다. 이후에는 READY 전이 이벤트만 증분 처리한다.

**Why this priority**: 신규 승인 건만 반영하면 기존 READY 음식 전체가 검색 후보에서 빠진 채 시작한다. 단, US1 의 파이프라인이 있어야 의미가 있으므로 후순위.

**Independent Test**: READY 음식 N건이 있는 상태에서 백필 실행 → N건의 PENDING UPSERT 아웃박스 생성 → 배치 실행 후 전량 적재 확인.

**Acceptance Scenarios**:

1. **Given** 아웃박스가 없는 기존 READY 음식들, **When** 백필을 실행하면, **Then** READY·활성 음식 전건에 대해 UPSERT/PENDING 아웃박스가 생성된다.
2. **Given** 백필로 생성된 아웃박스, **When** 배치가 반복 실행되면, **Then** 전 건이 COMPLETE 가 되고 벡터 저장소 문서 수가 READY 음식 수와 일치한다.

---

### User Story 4 - 관리자가 실패 건을 보고 재처리한다 (Priority: P4)

동기화가 최대 횟수를 넘겨 FAILED 가 된 아웃박스를 관리자가 조회하고, 원인(마지막 오류)을 확인한 뒤 재처리를 지시할 수 있다.

**Why this priority**: 실패는 자동 재시도가 1차 방어선이고, FAILED 잔존 건은 드물다. 운영 편의 기능이라 마지막 순위.

**Acceptance Scenarios**:

1. **Given** FAILED 아웃박스, **When** 관리자가 실패 목록을 조회하면, **Then** 대상 음식과 마지막 오류·시도 횟수를 확인할 수 있다.
2. **Given** FAILED 아웃박스, **When** 관리자가 재처리를 지시하면, **Then** 해당 건이 PENDING 으로 되돌아가 다음 배치에서 다시 처리된다.

---

### Edge Cases

- 저장소 반영 후 COMPLETE 기록 전에 장애가 나면? → 다음 실행에서 embeddingHash 동일을 확인하고 임베딩 호출 없이 COMPLETE 처리한다(at-least-once 여도 결과는 멱등).
- 같은 음식의 PENDING 아웃박스가 여러 건 쌓이면(승인 직후 연속 수정 등)? → 각 건이 처리 시점의 최신 데이터를 읽으므로 최종 상태로 수렴하고, hash 동일 건은 임베딩 없이 완료된다.
- UPSERT 아웃박스 처리 시점에 음식이 이미 삭제·READY 해제됐다면? → 검색 후보 자격이 없으므로 적재하지 않는다(문서가 있으면 제거).
- DELETE 아웃박스 처리 시점에 저장소에 문서가 없다면? → 오류가 아니라 COMPLETE 처리한다(멱등).
- 임베딩 대상 텍스트(긴 설명)가 비어 있으면? → 이름만으로 임베딩하지 않고 실패로 기록해 데이터 문제를 드러낸다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 관리자 승인(PENDING_REVIEW → READY)은 같은 트랜잭션 안에서 해당 음식의 UPSERT 벡터 아웃박스를 PENDING 으로 생성해야 한다.
- **FR-002**: READY 음식의 이름·설명·이미지 변경 경로는 UPSERT 아웃박스를, 삭제·READY 해제 경로는 DELETE 아웃박스를 생성해야 한다.
- **FR-003**: 아웃박스는 음식 식별자와 작업 종류(UPSERT | DELETE), 상태(PENDING | COMPLETE | FAILED), 시도 횟수, 마지막 오류만 기록한다 — 벡터·음식 내용을 복사하지 않는다.
- **FR-004**: 동기화 배치는 PENDING 아웃박스를 읽어 처리 시점의 최신 음식 데이터(이름 + 긴 설명)로 임베딩을 생성하고 벡터 저장소에 음식 단위로 upsert 해야 한다.
- **FR-005**: 벡터 저장소 문서는 음식 식별자(유일)·이름·긴 설명·이미지 참조·임베딩(벡터 검색 인덱스)·embeddingHash·임베딩 모델·차원·적재 시각을 가져야 한다.
- **FR-006**: 배치는 embeddingHash 로 멱등을 보장해야 한다 — 문서 없음이면 신규 적재, hash 동일이면 임베딩 호출 없이 완료, hash 변경이면 재임베딩 후 덮어쓰기.
- **FR-007**: 처리 실패 시 시도 횟수를 증가시키고 PENDING 을 유지하며, 최대 횟수 초과 시 FAILED 로 전환해야 한다.
- **FR-008**: 관리자는 FAILED 아웃박스를 조회하고 재처리(PENDING 복귀)할 수 있어야 한다.
- **FR-009**: 기존 READY·활성 음식 전건에 대해 일회성 백필로 UPSERT/PENDING 아웃박스를 생성할 수 있어야 한다.
- **FR-010**: UPSERT 처리 시점에 음식이 삭제·READY 해제 상태면 적재하지 않아야 하며, DELETE 처리 시점에 대상 문서가 없어도 완료로 처리해야 한다.

### Key Entities

- **벡터 아웃박스(food_vector_outbox)**: "이 음식은 벡터 저장소와 동기화가 필요하다"는 사실의 기록. 음식 식별자, 작업 종류(UPSERT | DELETE), 상태(PENDING | COMPLETE | FAILED), 시도 횟수, 마지막 오류를 가진다.
- **음식 벡터 문서**: 벡터 저장소에 음식당 하나 존재하는 검색 후보. 음식 식별자(유일), 표시 메타데이터(이름·긴 설명·이미지 참조), 임베딩과 그 출처 정보(embeddingHash·모델·차원·적재 시각)를 가진다.
- **음식(Food)**: 기존 엔티티. content_status 의 READY 여부가 검색 후보 자격을 결정하며, 이 기능은 음식 데이터를 소유하지 않고 읽기만 한다.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 관리자가 승인한 음식은 다음 동기화 배치 실행 안에 벡터 검색 후보로 조회 가능하다.
- **SC-002**: 내용이 변하지 않은 음식의 재처리는 임베딩 호출 0회로 완료된다 — 임베딩 호출 수가 실제 내용 변경 건수를 넘지 않는다.
- **SC-003**: 백필 완료 후 벡터 저장소의 문서 수가 READY·활성 음식 수와 일치한다.
- **SC-004**: 삭제·READY 해제된 음식은 다음 배치 실행 안에 벡터 검색 후보에서 사라진다.
- **SC-005**: 일시 장애(임베딩·저장소)는 운영자 개입 없이 재시도로 해소되고, 최대 횟수를 넘긴 건은 관리자 화면에서 원인과 함께 확인·재처리 가능하다.

## Assumptions

- 벡터 검색(읽기) 경로는 이미 구축되어 있다(KB-318) — 이 기능은 쓰기(적재) 갭을 메우는 것이며 검색 API 는 범위 밖이다.
- 임베딩 계약은 기존과 동일하다: 이름 + 긴 설명(long_description) 텍스트, 256차원, Bedrock Titan 모델. 긴 설명 컬럼은 이미 존재한다.
- 동기화는 별도 배치 앱의 주기 실행으로 동작하며 실시간성이 요구되지 않는다 — "다음 배치 실행 안에 반영"이면 충분하다.
- 아웃박스 생성 주체는 api 앱(관리자 승인·음식 변경 경로), 소비 주체는 배치 앱이다.
- 스캔 중 발견된 신규 음식의 이미지 처리(imageRef 비움, similarFood.imageRef 임시 응답)는 기존 동작을 유지하며 이 기능의 범위 밖이다.
- 랭체인 콘텐츠 적재(FAILED → PENDING_IMAGE → PENDING_REVIEW 구간)는 기존 파이프라인 그대로다 — 이 기능은 READY 전이 이후만 다룬다.

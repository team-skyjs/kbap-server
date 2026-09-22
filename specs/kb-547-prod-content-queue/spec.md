# Feature Specification: prod 콘텐츠 생성 요청 큐 분리

**Feature Branch**: `kb-547-prod-content-queue`

**Created**: 2026-09-12

**Status**: Draft

**Input**: User description: "KB-547 — dev·prod 가 SQS 큐 kbap-generate-content-queue 를 공유해 prod 요청이 dev 로 흘러간다. prod 전용 큐·DLQ 를 콘솔에서 만들고(보관 14일, 가시성 타임아웃·재시도 정책은 기존 큐와 동일), iac/terraform/prod.tfvars 의 food_content_queue_name 을 새 큐로 바꿔 terraform apply 로 prod 배치를 재배포한다. 소비자(prod Lambda) 연결은 별도 태스크(KB-549·KB-551)이며 그전까지 prod 요청은 새 큐에 쌓이기만 한다. dev Lambda 토큰 복구(KB-548)보다 먼저 해야 한다. 상세 배경·DoD 는 Jira KB-547."

## 배경

- 음식 콘텐츠 생성 요청은 배치가 요청 큐에 발행하고, kbap-langchain 의 Lambda(`kbap-generate-content`)가 소비해 결과를 kbap 적재 API 로 보낸다.
- dev 와 prod 가 같은 큐(`kbap-generate-content-queue`)를 쓴다. 테라폼은 큐를 만들지 않고 이름으로만 참조한다 — dev 는 변수 기본값, prod 는 로컬 `prod.tfvars` 에 같은 이름을 명시해 결국 한 큐를 공유한다.
- Lambda 는 결과를 dev API 로만 보낸다. 그래서 prod 음식은 콘텐츠를 받지 못하고(2026-09-12 기준 prod FAILED 음식 173건의 요청이 전부 SENT 에 멈춤), prod 요청의 outbox id·food id 가 dev 것과 우연히 겹치면 prod 콘텐츠가 dev 음식에 기록될 위험도 있다.
- dev Lambda 는 현재 토큰 만료로 적재가 401 로 실패 중이다(KB-548). 토큰을 먼저 복구하면 prod 요청이 다시 dev 로 전달되므로 이 작업이 선행이다.
- 구 운영 배치(`kbap-prod-cluster`)는 INACTIVE 라 prod 발행 경로는 ECS prod 배치 하나뿐이다(2026-08-31 결정).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - prod 요청이 dev 로 흘러가지 않는다 (Priority: P1)

운영자로서 prod 에서 발행된 콘텐츠 생성 요청이 dev 소비자에게 전달되지 않기를 원한다. 그래야 prod 콘텐츠가 dev 음식에 잘못 기록될 위험이 사라지고, dev 파이프라인 복구(KB-548)를 안전하게 진행할 수 있다.

**Why this priority**: 교차 환경 오염 경로를 차단하는 것이 이 작업의 존재 이유이며, KB-548 의 선행 조건이다.

**Independent Test**: prod 에서 콘텐츠 생성 요청을 하나 발행시킨 뒤, 그 메시지가 prod 전용 큐에만 있고 공유 큐로는 들어오지 않았는지 확인한다.

**Acceptance Scenarios**:

1. **Given** prod 배치가 prod 전용 큐를 바라보도록 재배포된 상태, **When** prod 에서 새 콘텐츠 생성 요청이 발행되면, **Then** 그 메시지는 prod 전용 큐에 쌓이고 공유 큐(`kbap-generate-content-queue`)의 메시지 수는 prod 발행으로 늘지 않는다.
2. **Given** 재배포 완료 후, **When** prod 배치 실행 환경의 발행 대상 큐 설정을 조회하면, **Then** prod 전용 큐를 가리킨다.
3. **Given** 재배포 완료 후, **When** dev 배치가 요청을 발행하면, **Then** dev 는 기존과 동일하게 공유 큐로 발행한다(dev 동작 무변경).

---

### User Story 2 - 소비자 연결 전까지 prod 요청이 유실되지 않는다 (Priority: P2)

운영자로서 prod 소비자(prod Lambda, KB-549·KB-551)가 연결되기 전까지 prod 요청이 큐에 보존되기를 원한다. 그래야 소비자 연결 시점에 쌓인 요청을 그대로 처리할 수 있다.

**Why this priority**: 분리 자체는 P1 로 달성되지만, 소비자 연결까지의 공백 동안 요청이 만료되면 재발행 작업이 추가로 생긴다.

**Independent Test**: prod 전용 큐·DLQ 의 보관 기간이 최댓값(14일)인지, 소비자가 없는 상태에서 발행된 메시지가 큐에 남아 있는지 확인한다.

**Acceptance Scenarios**:

1. **Given** prod 전용 큐·DLQ 가 생성된 상태, **When** 보관 기간 설정을 조회하면, **Then** 둘 다 14일이다.
2. **Given** prod 전용 큐에 소비자가 연결되지 않은 상태, **When** prod 요청이 발행되면, **Then** 메시지는 소비되지 않고 큐에 대기한다.
3. **Given** prod 전용 큐, **When** 가시성 타임아웃과 재시도 정책(최대 수신 횟수·DLQ 연결)을 조회하면, **Then** 기존 공유 큐와 같은 값이고 실패 메시지는 prod 전용 DLQ 로 간다.

---

### User Story 3 - 다음 셋업에서 prod 가 공유 큐로 회귀하지 않는다 (Priority: P3)

인프라 관리자로서 prod 설정 파일을 예시에서 새로 만들 때도 prod 전용 큐가 지정되기를 원한다. 실제 `prod.tfvars` 는 git 에 없어서, 예시에 큐 이름이 없으면 변수 기본값(공유 큐)으로 조용히 되돌아간다.

**Why this priority**: 현재 환경에는 영향이 없고, 재구성·신규 머신 셋업 때의 회귀만 막는다.

**Independent Test**: 저장소의 prod 설정 예시가 prod 전용 큐 이름을 명시하는지 확인한다.

**Acceptance Scenarios**:

1. **Given** 저장소의 prod 설정 예시 파일, **When** 내용을 보면, **Then** 콘텐츠 요청 큐 이름이 prod 전용 큐로 명시돼 있다.

---

### Edge Cases

- **전환 순간의 발행**: 재배포 도중 구 배치 태스크가 아직 살아 있으면 그 사이 발행분이 공유 큐로 갈 수 있다. 재배포는 발행 잡이 돌지 않는 시점에 하고, 완료 후 공유 큐로 새 prod 메시지가 들어오지 않았는지 확인한다.
- **이미 공유 큐·DLQ 에 있는 prod 메시지**: 옮기거나 재처리하지 않는다. KB-548 방침대로 자연 만료시킨다(dev·prod 요청이 섞여 있어 구분 재처리가 불가).
- **SENT 에 멈춘 prod 요청 173건**: 이 작업으로 복구되지 않는다. 새 큐로 재발행되지도 않는다(배치는 SENT 를 재발행하지 않음) — dev READY 음식 이관·prod Lambda 가동(KB-551) 쪽에서 다룬다.
- **prod 전용 큐 이름 오타·미생성**: 테라폼이 이름으로 큐를 조회하므로 큐가 없으면 apply 계획 단계에서 실패한다 — prod 배치가 잘못된 큐로 배포되지 않는다. 큐를 먼저 만들고 apply 한다.
- **배치 발행 권한**: 배치 태스크 롤의 발행 권한은 큐 ARN 에 한정돼 있다. apply 로 새 큐 ARN 으로 바뀌어야 하며, 옛 공유 큐에 대한 prod 배치 권한은 사라진다.
- **보관 기간 초과**: prod 소비자 연결이 14일을 넘기면 가장 오래된 요청부터 만료된다. 만료된 요청은 관리자 재요청으로 재발행해야 한다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: prod 전용 콘텐츠 생성 요청 큐와 그 DLQ 가 존재해야 한다(콘솔 생성, 테라폼 비관리 — 기존 공유 큐와 같은 운영 방식).
- **FR-002**: prod 전용 큐와 DLQ 의 메시지 보관 기간은 최댓값인 14일이어야 한다.
- **FR-003**: prod 전용 큐의 가시성 타임아웃과 재시도 정책(최대 수신 횟수)은 기존 공유 큐와 같아야 하며, 재시도를 소진한 메시지는 prod 전용 DLQ 로 가야 한다.
- **FR-004**: prod 배치는 prod 전용 큐로만 콘텐츠 생성 요청을 발행해야 한다(로컬 `prod.tfvars` 의 큐 이름 변경 + prod 인프라 반영·배치 재배포).
- **FR-005**: prod 배치의 발행 권한은 prod 전용 큐로 한정되어야 한다(공유 큐 발행 권한 제거).
- **FR-006**: dev 의 발행 대상과 dev 소비자(dev Lambda) 설정은 바뀌지 않아야 한다.
- **FR-007**: prod 전용 큐에는 이 작업에서 소비자를 연결하지 않는다(KB-549·KB-551 소관).
- **FR-008**: 저장소의 prod 설정 예시 파일은 prod 전용 큐 이름을 명시해야 한다.
- **FR-009**: 공유 큐·공유 DLQ 에 이미 들어간 메시지는 이동·재처리하지 않는다.

### Key Entities

- **prod 콘텐츠 생성 요청 큐**: prod 배치가 발행하는 요청의 대기열. 보관 14일, 가시성 타임아웃·최대 수신 횟수는 공유 큐와 동일, 실패 시 prod DLQ 로 이동.
- **prod 콘텐츠 생성 요청 DLQ**: 재시도를 소진한 prod 요청의 보관소. 보관 14일(공유 DLQ 의 4일보다 길게 — 소비자 연결 전 장기 보존 목적).
- **공유 큐(`kbap-generate-content-queue`)**: 이 작업 이후 dev 전용으로 남는다. 이름은 유지한다.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 전환 후 prod 에서 발행된 콘텐츠 생성 요청 중 공유(dev) 큐로 들어간 것이 0건이다.
- **SC-002**: 전환 후 prod 에서 발행된 요청 100% 가 prod 전용 큐에서 확인된다(검증 시점 발행분 기준).
- **SC-003**: prod 전용 큐·DLQ 모두 보관 기간 14일, 가시성 타임아웃·최대 수신 횟수가 공유 큐와 일치한다(설정 대조 3항목 전부 일치).
- **SC-004**: 전환 전후로 dev 콘텐츠 생성 요청의 발행 대상이 바뀌지 않는다(dev 발행분 100% 가 공유 큐로 감).
- **SC-005**: 이 작업이 KB-548(dev 토큰 복구)보다 먼저 완료된다.

## Assumptions

- 새 큐 이름은 환경 접두를 붙인 `kbap-prod-generate-content-queue`, DLQ 는 `kbap-prod-generate-content-dlq` 로 한다(기존 리소스 명명 `kbap-<env>-…` 관례). 다른 이름을 원하면 plan 단계에서 바꾼다.
- 공유 큐의 가시성 타임아웃·최대 수신 횟수는 팀 CLI 프로필로 조회할 수 없어(조회 권한 없음) 콘솔에서 확인해 그대로 옮긴다.
- 큐 암호화·접근 정책 등 나머지 속성은 콘솔 기본값을 쓴다 — 배치 발행 권한은 IAM 롤이 큐 ARN 으로 한정하므로 큐 정책 추가가 필요 없다.
- 실제 `prod.tfvars` 는 gitignore 대상이라 저장소 변경은 예시 파일(FR-008)과 문서뿐이고, 큐 생성·apply 는 운영자 수동 절차다. 변수 기본값(공유 큐 이름)은 dev 가 쓰므로 바꾸지 않는다.
- apply 는 팀 계정 `118178010621`·`ap-northeast-2` 에서 수행하며, 실행 전 계정을 확인한다.
- prod 배치는 현재 1대 가동 중(2026-08-31 결정)이며 구 운영 배치는 INACTIVE 라 다른 prod 발행 경로는 없다.
- 소비자 연결(KB-549·KB-551)과 dev 복구(KB-548), SENT 에 멈춘 요청 복구는 범위 밖이다.

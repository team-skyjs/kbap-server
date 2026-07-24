# Feature Specification: Flyway 마이그레이션 점 구분 timestamp 버전 규칙 전환

**Feature Branch**: `kb-44-flyway-timestamp-versioning`

**Created**: 2026-07-05

**Status**: Draft

**Input**: User description: "jira kb-44 작업 빠르게 끝내보자 + Flyway 공식 migrations 문서 기반 선택지 제안" → 선택: **C안(점 구분 timestamp `V2026.07.05.14.30.12__desc.sql`) + `out-of-order=true` + 마이그레이션 순서-독립 원칙**

## 변경 이력 (구현 중 피벗)

- **2026-07-05 구현 단계**: DB 가 **로컬 전용·프로덕션 이전**임이 확인되어, 기존 정수 마이그레이션(`V1`~`V10`)도 **각 파일의 최초 커밋 시각 기준 timestamp 로 일괄 리네임**(파일 내용 불변, 버전만 변경)했다. 같은 커밋에 묶인 파일은 초를 1초씩 밀어 원래 순서를 보존. 로컬 `flyway_schema_history` 는 DB 재생성으로 갱신했다.
- 이로써 아래 **FR-003·FR-008·SC-003 의 "기존 파일 절대 불변" 전제는 완화**된다: 파일 **내용**은 여전히 불변이나 **파일명(버전)** 은 일회성 전환됨. 단 **"공유/프로덕션 DB 에 이미 적용된 마이그레이션은 그 시점부터 동결"** 규칙은 유지된다(이번 전환은 프로덕션 이전이라 안전했던 일회성 예외).

## Overview

Flyway 마이그레이션 버전을 단순 증가 정수(`V1`…`V10`)에서 **점 구분 timestamp(`Vyyyy.MM.dd.HH.mm.ss__description.sql`)** 로 전환한다. 스키마 owner 는 `app:api` 이고 DB 를 공유하는 구조라, 여러 개발자가 병렬 브랜치에서 각자 다음 정수를 잡으면 머지 시 같은 버전 번호가 충돌한다. 초 단위 timestamp 는 이 충돌을 사실상 제거하고, 파일명만으로 생성 시점·목적을 알 수 있게 한다.

이 포맷은 Flyway 공식 문서(`concepts/migrations`)가 **유효한 버전 포맷으로 명시한 예시**(`2013.01.15.11.35.56`)를 그대로 따른 것이다. 다만 문서의 기본 권장은 "단순 증가 정수"이고 timestamp 는 허용 포맷이므로, 본 전환은 문서 강제가 아니라 **팀의 병렬 개발 특성상 선택**임을 전제한다.

문서는 정렬을 *"versions are sorted numerically"* 로 정의하고, 생성 시각 기반 버전이 뒤늦게 머지될 때 발생하는 **out-of-order** 상태를 *"rerunning the entire migration history might produce different results"* 로 경고한다. 따라서 본 전환은 **`out-of-order` 허용 설정**과 **마이그레이션 순서-독립 작성 원칙**을 함께 도입해 그 위험을 통제한다.

이 작업의 산출물은 **팀 컨벤션 문서화 + Flyway 설정 1줄 + 로컬 검증**이며, 이미 적용된 기존 정수 버전 파일은 절대 수정·리네임하지 않는다.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 신규 마이그레이션을 점 구분 timestamp 로 작성 (Priority: P1)

새 스키마 변경이 필요한 개발자가 컨벤션 문서를 열고, 신규 마이그레이션 파일명을 `Vyyyy.MM.dd.HH.mm.ss__description.sql` 포맷(생성 시각 초 단위)으로 지어야 함을 확인해 그대로 따를 수 있다.

**Why this priority**: 핵심 가치(병렬 브랜치 버전 충돌 제거)는 신규 마이그레이션이 timestamp 규칙을 따를 때 실현된다. 문서화된 규칙이 없으면 규칙이 없는 것과 같다.

**Independent Test**: 컨벤션 문서만 읽고 신규 마이그레이션 파일 하나를 규칙(생성 시각 초 단위 점 구분 + 설명 슬러그)에 맞게 만들 수 있는지로 검증한다.

**Acceptance Scenarios**:

1. **Given** 개발자가 새 마이그레이션을 추가하려 함, **When** 컨벤션 문서를 참고, **Then** `Vyyyy.MM.dd.HH.mm.ss__description.sql`(예: `V2026.07.05.14.30.12__add_review_table.sql`) 포맷과 "생성 시점 로컬 현재 시각(초 단위)" 규칙을 확인해 파일명을 지을 수 있다.
2. **Given** 개발자가 정수 버전(`V11`)으로 신규 파일을 만들려 함, **When** 문서의 금지 사례를 확인, **Then** 신규 파일에 정수 버전을 쓰는 것이 금지임을 인지하고 timestamp 포맷으로 전환한다.

---

### User Story 2 - 기존 정수 이력과의 공존·순서 이해 (Priority: P1)

기존 코드베이스를 유지보수하는 개발자가, 이미 공유 DB 에 적용된 `V1`~`Vn` 정수 파일을 수정·리네임하면 안 된다는 것과, 정수 이력과 신규 점 구분 timestamp 버전이 공존하며 Flyway 가 숫자 정렬로 순서를 정한다는 것, 그리고 **뒤늦게 머지된 과거 timestamp 는 out-of-order 로 적용**된다는 것을 문서로 이해한다.

**Why this priority**: 기존 파일을 잘못 건드리면 Flyway checksum/history 가 깨져 배포 장애가 난다. 또 out-of-order 동작을 모르면 "뒤늦게 머지된 마이그레이션이 왜 순서가 뒤죽박죽인가"를 오해해 잘못 대응한다. P1 규칙을 안전하게 쓰기 위한 필수 지식이라 동일 P1.

**Independent Test**: 문서를 읽고 (a) 기존 파일 수정/리네임 가부, (b) 정수와 timestamp 공존 이유(`V10` < `V2026.07.05...`), (c) out-of-order 가 언제 발생하고 왜 허용되는지를 설명할 수 있는지로 검증한다.

**Acceptance Scenarios**:

1. **Given** 기존 `V1`~`V10` 파일이 있는 상태, **When** 개발자가 문서를 참고, **Then** 기존 파일을 수정·리네임하지 않아야 함을 인지한다.
2. **Given** 정수 버전과 점 구분 timestamp 가 혼재, **When** Flyway 가 순서를 정함, **Then** 숫자 정렬로 기존 정수 이력이 앞서고 신규 timestamp 가 뒤따름을 이해한다.
3. **Given** 늦게 머지된 과거 timestamp 마이그레이션(이미 적용된 최신 버전보다 과거), **When** 배포 시 Flyway 가 실행, **Then** `out-of-order` 허용으로 정상 적용되며 실행 순서(installed_rank)가 버전 순서와 달라질 수 있음을 이해한다.

---

### User Story 3 - out-of-order 허용 설정과 마이그레이션 독립성 원칙 (Priority: P1)

담당자가 `out-of-order` 허용 설정을 적용하고, 컨벤션 문서에 "각 마이그레이션은 실행 순서에 의존하지 않도록 독립적으로 작성한다"는 원칙을 명시해, out-of-order 상황에서도 결과가 달라지지 않도록 한다.

**Why this priority**: 문서가 경고한 *"rerunning the entire migration history might produce different results"* 위험은 설정만으로는 못 막는다. 마이그레이션이 서로 순서-독립적일 때만 out-of-order 가 안전하다. 설정과 원칙이 한 쌍이라 P1.

**Independent Test**: 설정이 적용되어 out-of-order 마이그레이션이 부팅을 막지 않는지, 그리고 문서에 순서-독립 원칙과 그 이유가 기술되어 있는지로 검증한다.

**Acceptance Scenarios**:

1. **Given** `out-of-order` 허용 설정이 적용됨, **When** 과거 timestamp 마이그레이션이 뒤늦게 적용, **Then** validate 실패 없이 정상 부팅된다.
2. **Given** 컨벤션 문서, **When** 개발자가 신규 마이그레이션을 작성, **Then** "순서-독립 작성(다른 미적용 마이그레이션의 실행 순서에 의존 금지)" 원칙과 근거(out-of-order 시 결과 불변 보장)를 확인한다.

---

### User Story 4 - 로컬 MySQL 정렬·out-of-order·checksum 검증 (Priority: P2)

담당자가 점 구분 timestamp 신규 마이그레이션을 로컬 docker MySQL 에서 실제로 적용해, 기존 정수 이력 뒤에 정상 적용되고, 과거 timestamp 를 뒤늦게 넣어도 out-of-order 로 적용되며, 기존 checksum 이 깨지지 않음을 확인한다.

**Why this priority**: 문서상 "공존·out-of-order 가능"을 실제 Flyway 실행으로 입증해 규칙 신뢰성을 확보한다. 규칙·설정 도입은 P1 로 완결되므로 실증은 P2.

**Independent Test**: 로컬 docker MySQL 에 기존 이력을 적용 → 최신보다 과거인 timestamp 신규 마이그레이션을 추가·부팅 → `flyway_schema_history` 정상 기록 + 기존 checksum validate 통과를 확인.

**Acceptance Scenarios**:

1. **Given** 기존 정수 이력이 적용된 로컬 MySQL, **When** 점 구분 timestamp 신규 마이그레이션을 추가하고 앱을 부팅, **Then** 신규 마이그레이션이 정상 적용된다.
2. **Given** 이미 적용된 최신 버전보다 과거인 timestamp 마이그레이션, **When** 부팅, **Then** out-of-order 로 정상 적용되고 부팅이 막히지 않는다.
3. **Given** 기존 정수 파일을 건드리지 않은 상태, **When** Flyway validate 실행, **Then** 기존 checksum 검증이 깨지지 않는다.

---

### Edge Cases

- **같은 초에 두 개발자가 각각 마이그레이션 생성** → 버전 동일 충돌 가능하나 초 단위라 확률 극히 낮음. 충돌 시 한쪽 timestamp 를 1초 조정하도록 문서 안내.
- **점 구분 파트 자릿수 실수**(예: 월/일을 한 자리로 `2026.7.5...`) → Flyway 는 숫자 정렬이라 파트 값 자체로 정렬되므로 치명적이진 않으나, 일관성을 위해 두 자리 zero-pad(`07`, `05`)를 문서 예시로 고정.
- **기존 정수 파일을 "일관성" 이유로 timestamp 로 리네임하려는 유혹** → 금지 사례로 명시(checksum/history 파손).
- **out-of-order 를 악용해 순서 의존 마이그레이션을 작성** → 순서-독립 원칙 위반. 문서가 금지로 명시.
- **`out-of-order` 설정 누락 시** → 과거 timestamp 뒤늦은 머지가 validate 실패로 배포를 막음. 설정을 베이스 프로필에 두어 전 환경 일관 적용.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 컨벤션 문서는 신규 Flyway 마이그레이션이 `Vyyyy.MM.dd.HH.mm.ss__description.sql`(점 구분, 초 단위, 각 파트 zero-pad) 포맷을 사용해야 함을 명시해야 한다.
- **FR-002**: 문서는 버전 timestamp 를 **마이그레이션 생성 시점의 로컬 현재 시각(초 단위)** 기준으로 생성한다고 명시해야 한다.
- **FR-003**: 문서는 이미 적용된 기존 `V1`~`Vn` 정수 파일을 수정·리네임하지 않으며, 정수 이력과 timestamp 버전이 숫자 정렬로 공존함(`V10` < `V2026.07.05.14.30.12`)을 설명해야 한다.
- **FR-004**: 문서는 올바른 예시 파일명과 금지 사례(신규에 정수 `V11` 사용, 기존 파일 리네임, 순서 의존 마이그레이션 작성)를 포함해야 한다.
- **FR-005**: 시스템은 뒤늦게 머지된 과거 timestamp 마이그레이션이 배포를 막지 않도록 **Flyway out-of-order 적용을 허용**해야 한다(`spring.flyway.out-of-order=true`, 전 실행 프로필 일관 적용).
- **FR-006**: 문서는 out-of-order 허용의 전제로 **각 마이그레이션을 순서-독립적으로 작성**(다른 미적용 마이그레이션의 실행 순서에 의존 금지)한다는 원칙과 그 근거(문서 경고: 재실행 시 결과 상이 가능)를 명시해야 한다.
- **FR-007**: 컨벤션·설정 적용 후 로컬 docker MySQL 에서 (a) 신규 timestamp 마이그레이션 정상 적용, (b) 과거 timestamp 의 out-of-order 정상 적용, (c) 기존 checksum 무결성을 확인해야 한다.
- **FR-008**: 이 작업은 기존 마이그레이션 SQL·DB 스키마·애플리케이션 코드를 변경하지 않는다(문서화 + Flyway 설정 1줄 + 검증 전용).

### Key Entities *(include if feature involves data)*

- **Flyway 마이그레이션 파일**: `app/api/src/main/resources/db/migration/` 아래 버전 스크립트. 파일명 = 버전 접두어(`V<정수>` 또는 `V<yyyy.MM.dd.HH.mm.ss>`) + `__` + 설명 슬러그 + `.sql`. 버전 접두어가 이 작업의 대상.
- **Flyway 설정(out-of-order)**: 실행 환경의 Flyway 동작 설정. 과거 버전의 뒤늦은 적용을 허용할지 결정하며, 전 프로필에 일관 적용되어야 한다.
- **`flyway_schema_history`**: 적용 이력·checksum·installed_rank(실제 실행 순서)를 보관하는 Flyway 관리 테이블. 기존 정수 이력 보존 및 out-of-order 검증 시 참조.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 개발자가 컨벤션 문서만 보고 별도 질문 없이 규칙에 맞는 신규 점 구분 timestamp 마이그레이션 파일명을 지을 수 있다.
- **SC-002**: 문서에 올바른 예시 1건 이상과 금지 사례 3건 이상(신규 정수 버전, 기존 파일 리네임, 순서 의존 마이그레이션)이 포함된다.
- **SC-003**: 기존 `V1`~`V10` 마이그레이션 파일이 이 작업 전후로 내용·파일명 모두 100% 변경되지 않는다.
- **SC-004**: 로컬 docker MySQL 에서 기존 이력 + 신규 timestamp + 과거 timestamp(out-of-order) 부팅 시 Flyway 오류 0건, 기존 checksum validate 통과.
- **SC-005**: out-of-order 허용 설정이 전 실행 프로필에서 활성 상태로 확인된다(과거 timestamp 마이그레이션이 부팅을 막지 않음).

## Assumptions

- 이 작업은 **문서화 + Flyway 설정 + 로컬 검증** 중심이며, 새 비즈니스 기능이나 스키마 변경을 포함하지 않는다.
- 채택 포맷은 Flyway 공식 문서가 유효 예시로 제시한 점 구분 timestamp(`2013.01.15.11.35.56` 형)를 따르며, 초 단위·두 자리 zero-pad 로 통일한다.
- 컨벤션 문서 위치는 CLAUDE.md 의 Flyway/DB 규약 섹션과 `docs/architecture/meogo-conventions.md`(또는 동급 DB 컨벤션 문서) 중 프로젝트 관례에 맞는 곳으로 하며, 세부 위치는 plan 단계에서 확정한다.
- `out-of-order` 허용은 마이그레이션들이 서로 순서-독립적이라는 전제 위에서만 안전하며, 이 전제를 문서 원칙으로 강제한다.
- 검증 환경은 기존 관례대로 로컬 docker MySQL(`meogo-mysql`)이며, 테스트(H2·flyway off)는 마이그레이션을 실행하지 않으므로 검증 대상이 아니다.
- 현재 `application.yml` 에 Flyway 세부 설정이 없어 전부 기본값(`out-of-order=false`) 상태이므로, out-of-order 허용은 명시적 설정 추가가 필요하다.

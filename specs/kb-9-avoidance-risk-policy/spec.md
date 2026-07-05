# Feature Specification: 기피성분 포함 확률 기반 위험도 정책 + 음식 종합 위험도 판정

**Feature Branch**: `kb-9-avoidance-risk-policy`

**Created**: 2026-07-05

**Status**: Draft

**Input**: User description: "jira kb-9를 시작할거야. 태스크 설명 중에 음식에 포함되는 기피 성분의 퍼센티지를 기준으로 위험도를 측정하는 정책, 그리고 위험도를 종합판단하여 음식에 대한 위험도 판단도 내려주는 로직을 구현하면 돼. 음식 상세 조회 과정에서 현재 목킹하여 돌아가는 위험도 판단을 대체하는 것임."

## 개요 (Context)

음식 상세 조회는 각 기피성분에 대해 위험도(riskStatus)를, 그리고 음식 전체에 대한 종합 위험도를 제공해야 한다. 현재 성분별 위험도는 목(mock)으로 채워지고 있으며(첫 성분만 CAUTION, 나머지 SAFE), 음식 전체 종합 위험도는 아예 없다. 이 기능은 (1) 포함 확률(percentage) 기반의 **실제 성분별 위험도 정책**과 (2) 사용자가 회피하는 성분을 종합해 내리는 **음식 단위 종합 위험도 판정**을 도입하여 목을 대체한다.

사용자 도메인(개인 기피 프로필)과 인증은 아직 구현되지 않았으므로, "사용자가 회피하는 성분 목록"은 이번 범위에서 **목(mock) 제공자**로 조달한다. 다만 교집합 산출과 위험도 계산 로직 자체는 실제로 동작하며, 이후 실제 프로필/인증으로 조달원만 교체할 수 있는 이음새(seam)로 둔다.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 포함 확률 기반 성분별 실제 위험도 (Priority: P1)

음식 상세를 조회하는 사용자는 각 기피성분에 대해, 그 성분이 음식에 포함될 확률에 근거한 **실제** 위험도(안전/위험/금지)를 본다. 더 이상 목 값이 아니다.

**Why this priority**: 목 대체의 핵심이자 최소 가치. 확률 기반 성분별 위험도만으로도 사용자는 "이 성분이 얼마나 위험한지"를 신뢰할 수 있게 되어 즉시 가치가 생긴다. 종합 판정은 이 위에 쌓인다.

**Independent Test**: 여러 포함 확률의 성분이 든 음식을 상세 조회해, 각 성분 riskStatus가 확률→위험도 정책(경계값 포함)과 일치하는지로 단독 검증 가능.

**Acceptance Scenarios**:

1. **Given** 포함 확률 5%인 성분, **When** 음식 상세를 조회하면, **Then** 그 성분 위험도는 SAFE.
2. **Given** 포함 확률 10%인 성분, **When** 조회하면, **Then** CAUTION(경계 포함).
3. **Given** 포함 확률 59%인 성분, **When** 조회하면, **Then** CAUTION.
4. **Given** 포함 확률 60%인 성분, **When** 조회하면, **Then** DANGER(경계 포함).
5. **Given** 포함 확률 100%인 성분, **When** 조회하면, **Then** DANGER.
6. **Given** 여러 성분이 든 음식, **When** 조회하면, **Then** 각 성분 riskStatus가 목이 아니라 각자의 확률로 산출된 값이다.

---

### User Story 2 - 사용자 회피 성분 기반 음식 종합 위험도 (Priority: P1)

음식 상세를 조회하는 사용자는 음식 하나에 대한 **종합 위험도**를 한눈에 본다. 종합 위험도는 사용자가 회피하는 성분 중 음식에 포함된 것들만을 대상으로, 그중 가장 심각한 판정으로 결정된다.

**Why this priority**: 태스크가 명시적으로 요구하는 "음식에 대한 위험도 판단"이며, 사용자가 개별 성분을 일일이 해석하지 않고도 먹어도 되는지 즉시 판단하게 해준다.

**Independent Test**: 성분 집합·포함 확률과 (목) 회피 목록을 달리한 음식들을 조회해, overallRiskStatus가 최악값 규칙과 일치하는지로 단독 검증 가능.

**Acceptance Scenarios**:

1. **Given** 사용자 회피 성분 중 음식에 포함된 것이 하나라도 DANGER 판정, **When** 조회하면, **Then** overallRiskStatus = DANGER.
2. **Given** DANGER는 없고 회피·포함 교집합 중 하나라도 CAUTION, **When** 조회하면, **Then** overallRiskStatus = CAUTION.
3. **Given** 회피·포함 교집합이 전부 SAFE, **When** 조회하면, **Then** overallRiskStatus = SAFE.
4. **Given** 사용자 회피 성분이 음식에 하나도 포함되지 않음(교집합 공집합), **When** 조회하면, **Then** overallRiskStatus = SAFE.
5. **Given** 음식이 기피성분을 하나도 갖지 않음, **When** 조회하면, **Then** overallRiskStatus = SAFE.
6. **Given** 임의 음식, **When** 조회하면, **Then** 응답 최상위에 overallRiskStatus 필드가 존재한다.

---

### User Story 3 - 판정 불가 시 안전 오도 없이 UNKNOWN (Priority: P2)

위험도를 확정할 수 없는 상황에서 사용자는 SAFE로 오도되지 않고 **UNKNOWN**(판정 불가)을 명확히 본다.

**Why this priority**: 안전 관련 기능에서 "모름"을 "안전"으로 표기하는 것은 사용자를 위험에 빠뜨린다. 정확성·신뢰의 하한선이지만, P1 흐름이 먼저 동작해야 검증 가능하므로 P2.

**Independent Test**: 미등록/매칭 실패 음식과 확률 결측 성분을 가진 음식을 조회해, overallRiskStatus가 SAFE가 아니라 UNKNOWN인지로 단독 검증 가능.

**Acceptance Scenarios**:

1. **Given** 매칭되지 않는(미등록) 음식, **When** 조회하면, **Then** 종합 위험도는 UNKNOWN이며 SAFE가 아니다.
2. **Given** 등록된 음식이지만 판정 대상 회피 성분 중 하나라도 포함 확률 데이터가 결측, **When** 조회하면, **Then** overallRiskStatus = UNKNOWN.
3. **Given** 판정 불가 상황, **When** 조회하면, **Then** 어떤 경우에도 SAFE로 판정되지 않는다.

---

### Edge Cases

- **경계값**: p=9→SAFE, p=10→CAUTION, p=59→CAUTION, p=60→DANGER, p=100→DANGER.
- **음식에 기피성분 없음**: 성분 목록 비어 있고 overallRiskStatus=SAFE.
- **회피 목록(mock) 공집합**: 교집합 공집합 → overallRiskStatus=SAFE.
- **교집합에 결측 성분 포함**: §8에 따라 overallRiskStatus=UNKNOWN(다른 성분이 SAFE/CAUTION이어도 UNKNOWN 우선).
- **미등록 음식**: 종합 위험도 UNKNOWN — 현재 미등록 메뉴명은 400(NOT_FOUND)으로 응답하므로, 이 UNKNOWN 규칙과 기존 400 계약의 관계 확정 필요(Assumptions 참조).
- **성분별 riskStatus vs 종합 대상**: 성분별 riskStatus는 음식이 가진 각 성분의 확률로(사용자 무관) 산출하고, 종합은 사용자 회피 교집합만 대상으로 한다 — 두 값의 범위가 다를 수 있다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 시스템은 각 기피성분의 포함 확률 `p`(1~100)로 성분별 위험도를 산출한다 — `p < 10` → SAFE, `10 ≤ p < 60` → CAUTION, `p ≥ 60` → DANGER.
- **FR-002**: 음식 상세 응답의 각 성분 위험도는 목이 아니라 FR-001 정책의 실제 산출값이어야 한다.
- **FR-003**: 시스템은 음식 종합 위험도를, **사용자가 회피하는 성분 ∩ 음식이 포함하는 성분**을 대상으로, 그 대상들의 성분별 위험도 중 가장 심각한 값(심각도 DANGER > CAUTION > SAFE)으로 판정한다.
- **FR-004**: 판정 대상 교집합이 공집합이거나 대상이 전부 SAFE이면 음식 종합 위험도는 SAFE로 판정한다.
- **FR-005**: 시스템은 음식 종합 위험도를 상세 조회 응답의 최상위 `overallRiskStatus` 필드(SAFE/CAUTION/DANGER/UNKNOWN)로 제공한다.
- **FR-006**: 음식을 식별·매칭할 수 없는 경우(미등록/매칭 실패) 음식 종합 위험도는 UNKNOWN이며, 어떤 경우에도 SAFE로 판정하지 않는다.
- **FR-007**: 등록된 음식이라도 판정 대상 회피 성분 중 하나라도 포함 확률 데이터가 결측이면 음식 종합 위험도는 UNKNOWN이다.
- **FR-008**: 사용자의 회피 성분 목록은 사용자 도메인·인증이 미구현이므로 이번 범위에서 목(mock) 제공자로 조달한다. 단, 교집합 산출과 종합 위험도 계산은 실제로 동작해야 하며, 조달원을 향후 실제 프로필/인증으로 교체할 수 있는 이음새로 분리한다.
- **FR-009**: 기존의 목 위험도 산출(성분별 위험도를 임의로 채우는 로직)은 FR-001~FR-007 정책 구현으로 대체·제거된다.
- **FR-010**: 위험도 정책의 확률 임계값(10, 60)과 심각도 순서는 단일하게 정의되어, 성분별·종합 판정이 동일한 임계값·심각도 순서를 공유한다.

### Key Entities *(include if feature involves data)*

- **성분별 위험도(RiskLevel)**: SAFE / CAUTION / DANGER / UNKNOWN. 성분이 음식에 포함될 확률로부터 산출.
- **포함 확률(inclusionProbability)**: 특정 기피성분이 해당 음식에 포함될 확률(1~100). 위험도 산출의 입력.
- **음식 종합 위험도(overallRiskStatus)**: 음식 단위로 내려지는 하나의 RiskLevel. 사용자 회피 성분 ∩ 음식 포함 성분의 최악값(또는 판정 불가 시 UNKNOWN).
- **사용자 회피 목록(avoidList, mock)**: 사용자가 회피하는 기피성분 코드 집합. 이번 범위에선 목으로 제공, 종합 판정의 대상 필터.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 음식 상세 조회 응답의 성분별 위험도 100%가 FR-001 정책 산출값과 일치한다(목 값 0건).
- **SC-002**: 임의의 (성분·확률·회피목록) 조합에서 overallRiskStatus 100%가 최악값 규칙(FR-003·FR-004)과 일치한다.
- **SC-003**: 판정 불가(미등록·확률 결측) 케이스에서 SAFE로 오분류되는 건수 0. 전부 UNKNOWN.
- **SC-004**: 경계값(9/10/59/60/100)에서 성분별 위험도가 정책과 100% 일치.
- **SC-005**: 음식 상세 응답에 overallRiskStatus 필드가 100% 포함된다.

## Assumptions

- **사용자 회피 목록은 mock 제공**: `:core:member`·인증(JWT) 미구현. 실제 개인 프로필 연동은 후속 스토리(KB-9 범위 밖)이며, 이번엔 조달원만 목이고 교집합·판정 로직은 실제.
- **성분별 위험도는 음식 내재(사용자 무관)**: 응답의 각 성분 riskStatus는 그 성분의 포함 확률로 산출된다. 사용자 회피 교집합은 오직 종합(overallRiskStatus) 판정 대상에만 적용된다.
- **"확률 결측"의 의미**: 현재 도메인상 포함 확률은 1~100 필수값이므로, 결측은 성분 데이터/카탈로그 결측(예: 소프트삭제·미등록 성분으로 확률을 확정할 수 없는 상황)을 의미한다. 구체 판별 기준은 계획 단계에서 확정.
- **미등록 음식 ↔ 기존 400 계약**: 현재 미등록 메뉴명은 400(NOT_FOUND, "해당 음식 정보 없음")으로 응답한다. §5의 "미등록 음식 → UNKNOWN"을 (a) 200 응답에 overallRiskStatus=UNKNOWN으로 전환할지, (b) 기존 400을 유지하고 UNKNOWN 규칙은 "음식은 있으나 위험도 판정 불가"에만 적용할지는 `/speckit-plan`에서 확정한다. 어느 쪽이든 "판정 불가를 SAFE로 표기하지 않는다"는 원칙은 불변.
- **성분 목록 표기 범위**: 상세 응답의 성분 목록은 기존과 동일하게 음식이 포함하는 기피성분 전체를 노출한다(사용자 회피 목록으로 필터링하지 않는다). 종합 판정만 교집합 대상.
- **응답 계약 변경**: overallRiskStatus 최상위 필드 신설은 모바일 클라이언트와의 응답 계약 추가 변경이며, 기존 필드는 유지한다.

## Out of Scope

- 사용자 도메인(`:core:member`)·개인 기피 프로필의 실제 구현.
- 인증(JWT)·사용자 식별 흐름 구현.
- 성분 목록을 사용자 회피 목록으로 필터링해 노출하는 것.
- 위험도 사유(reason) 텍스트 제공(현 성분별 응답엔 사유 없음).

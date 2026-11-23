# Feature Specification: 음식 발견 푸시 (FOOD_DISCOVERY)

**Feature Branch**: `feat/kb985-food-discovery-push`

**Created**: 2026-11-04

**Status**: Draft

**Input**: KB-985 PRD + CTO 계획(`01_cto_plan.md` BE 절). 마케팅 동의 + 소식 알림이 켜진 회원에게 KST 09·12·15·18시마다 음식 하나를 골라 "🌟 {음식명} 드셔보셨어요?" 제목, 음식 설명 본문으로 광고성 푸시를 보낸다.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 안전한 음식 하나를 하루 네 번 받는다 (Priority: P1)

마케팅 동의와 소식 알림을 켠 회원은 09·12·15·18시마다 인기 음식 중 자기가 피하는 재료가 하나도 들지 않은 음식 하나를 푸시로 받고, 탭하면 그 음식 상세로 간다.

**Why this priority**: 기능의 본체. 회피 재료가 든 음식을 권하면 신뢰가 무너진다.

**Independent Test**: 회피 재료를 가진 회원과 후보 음식을 두고 잡을 실행해 보낸 음식·문구·data 를 확인한다.

**Acceptance Scenarios**:

1. **Given** 돼지고기를 피하는 회원과 돼지고기가 1% 포함된 음식, **When** 후보를 고르면, **Then** 그 음식은 절대 선택되지 않는다.
2. **Given** 상위 묶음 재료(예: 육류)를 고른 회원과 묶음에 속한 하위 재료만 든 음식, **When** 후보를 고르면, **Then** 그 음식은 선택되지 않는다.
3. **Given** 태국어 기기 회원과 태국어 설명 번역이 없는 음식, **When** 후보를 고르면, **Then** 그 음식은 선택되지 않는다. 기기가 둘(en·ja)이면 두 언어 설명이 다 있는 음식만 고른다.
4. **Given** 09:30 실행, **When** 대상 회원에게 발송하면, **Then** 제목 `(광고) 🌟 {음식명} 드셔보셨어요?`, 본문 `{설명}\n수신거부: …`, data `{type: FOOD_DISCOVERY, foodId, notificationId}` 이고 알림함 행에 food_id 가 남는다.

---

### User Story 2 - 같은 음식을 반복해서 받지 않는다 (Priority: P1)

**Acceptance Scenarios**:

1. **Given** 89일 전에 음식 A 를 받은 회원, **When** 후보를 고르면, **Then** A 는 제외된다. 91일 전이면 후보가 된다.
2. **Given** 발송 실패(soft delete 된 알림), **When** 이력을 볼 때, **Then** 이력으로 치지 않는다.
3. **Given** 북마크한 음식, **When** 후보를 고르면, **Then** 제외된다.

---

### User Story 3 - 운영 안전장치 (Priority: P2)

**Acceptance Scenarios**:

1. **Given** 같은 슬롯에 이미 받은 회원, **When** 잡을 다시 실행하면, **Then** 추가 발송은 0 이다.
2. **Given** KST 21:00~08:59, **When** 잡이 (수동으로라도) 실행되면, **Then** 발송은 0 이다.
3. **Given** 후보가 0 인 회원, **When** 잡이 실행되면, **Then** 발송 없이 `no_candidate{slot}` 메트릭이 오른다.

---

### User Story 4 - 알림함에서도 음식으로 간다 (Priority: P3)

**Acceptance Scenarios**:

1. **Given** FOOD_DISCOVERY 알림함 행, **When** `GET /api/notifications`, **Then** `foodId` = 음식 id. 다른 유형은 null.

### Edge Cases

- 후보 풀이 비었거나 모든 음식이 회피·이력·북마크로 빠지면 건너뛰고 메트릭만 올린다.
- READY 가 아니거나 재료 미평가(`ingredients == null`) 음식은 풀에서 뺀다.
- 기기 언어 KO 는 원문 `description`, 그 외는 `descriptionTranslations[lang]` 만 인정(한국어 폴백 금지). 이름은 기존 폴백 허용.
- 같은 음식이 뽑힌 회원들은 발송 1회로 묶는다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001** (R-01): 알림 유형 FOOD_DISCOVERY 는 광고성, 채널 news, 소식 토글 + 마케팅 동의 v2 둘 다 있을 때만 대상.
- **FR-002** (R-02): 10개 로케일 템플릿 `push.food_discovery.1.title/body`.
- **FR-003** (R-03): 후보 풀은 잡 실행당 1회 적재(`food_marketing_popular` ACTIVE 행 → READY·재료 평가된 음식).
- **FR-004** (R-04): 회피 재료(묶음 확장 포함)와 하나라도 겹치는 음식 제외. 포함 확률 무관. 안전 후보 계산은 고유 회피 집합마다 1회.
- **FR-005** (R-05): 회원 대상 기기 언어 전부에 설명이 있는 음식만.
- **FR-006** (R-06): 같은 회원 같은 음식 90일 내 재발송 금지. 실패 발송 제외.
- **FR-007** (R-07): 북마크 음식 제외.
- **FR-008** (R-08): 발송 알림 행에 food_id 저장(= data.foodId).
- **FR-009** (R-09): 09·12·15·18시 트리거, 슬롯당 멱등, KST 21~09시 발송 금지.
- **FR-010** (R-10): `kbap.push.dispatch{type=FOOD_DISCOVERY,result}` + `kbap.push.food_discovery.no_candidate{slot}`.
- **FR-011** (R-11): 마케팅 영수증 동기화를 09:00~20:50 10분 간격으로.
- **FR-012** (R-12): 알림함 응답 `foodId` 는 FOOD_DISCOVERY 면 음식 id, 그 외 null.

### Key Entities

- **Notification**: 회원 알림함 행. `food_id` 추가(조회용 사본, FK 없음).
- **후보 음식**: id, 재료 코드 집합, 언어별 (이름, 설명).

## Success Criteria *(mandatory)*

- **SC-001**: 회피 재료가 하나라도 든 음식이 선택되는 경우 0건(테스트로 증명).
- **SC-002**: 90일 내 같은 음식 재발송 0건.
- **SC-003**: 기기 언어 설명 번역 없는 음식 발송 0건.
- **SC-004**: 같은 슬롯 재실행·야간 실행 시 추가 발송 0건.

## Assumptions

- CTO 계획 A1~A10 그대로(data 에 href 대신 foodId, cron 1개, ShedLock 대신 기존 실행 제어, 야간 차단 코드화, 다언어 기기는 전 언어 설명 필요 등).
- 풀 보충(prod 10행)·대시보드는 범위 밖.

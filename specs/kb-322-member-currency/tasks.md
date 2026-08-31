---

description: "Task list for kb-322 회원 통화 설정"
---

# Tasks: 회원 통화 설정 — 국가 기반 자동 지정 및 프로필에서 개별 변경

**Input**: Design documents from `/specs/kb-322-member-currency/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 구현 전 실패 테스트를 작성하고 Red 를 눈으로 확인한다.

**Organization**: 스토리별 그룹. US1·US2·US3 는 P1, US4 는 P2.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완 task 에 의존 없음)
- 모든 경로는 저장소 루트 기준

---

## ⚠️ 시작 전 필독 — 이미 들어간 코드가 있다

`CurrencyCode`·`CountryCode` 두 enum 은 **테스트 없이 먼저 작성됐다**(환율 데이터 확보 과정에서 대화형으로 생성). 헌법 원칙 I 이탈이며, **T004~T006 이 사후 테스트로 이를 덮는다.**

또한 **설계 문서가 낡았다** — spec·research·data-model 이 `frankfurter(30종)`·`통화 146종`·`krwPerUnit nullable`·`197개 전수 매핑` 전제로 쓰여 있으나, 실제는 다음과 같다:

| 항목 | 문서(낡음) | 실제 |
|------|-----------|------|
| 환율 출처 | frankfurter(ECB) | **은행 고시 매매기준율** 단일 스냅샷 |
| 통화 수 | 146종 | **46종**(지정 45 + KRW) |
| 환율 보유 | 30종, 나머지 null | **46종 전부**, `krwPerUnit` non-null |
| 국가 매핑 | 197개 각자의 실제 통화 | 취급 통화 사용국 80개는 실제 통화, **나머지 117개는 USD 대체** |
| 소수 자릿수 | (미정) | **4자리 통일** |

T024~T026 이 문서를 실제에 맞춘다. **문서를 먼저 읽고 코드를 짜면 틀린다** — 이 표가 우선한다.

---

## Phase 1: Setup (기준선 확보)

**Purpose**: 변경 전 상태를 고정해 이후 Red/Green 판정이 신뢰 가능하게 만든다

- [x] T001 `./gradlew :common:test :api:test --tests "com.kbap.api.member.*"` 를 실행해 **현재 전부 통과**함을 확인한다 (enum 2개가 이미 들어가 있으므로 컴파일 포함)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 통화 타입과 에러 코드가 있어야 어떤 스토리도 시작할 수 없다

**⚠️ CRITICAL**: T002~T006 완료 전에는 US1~US4 를 시작하지 않는다

- [x] T002 `common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt` 에 `INVALID_CURRENCY_CODE("MEMBER-010", 400, "지원하지 않는 통화 코드입니다")` 추가. **007 은 폐기 번호라 재사용 금지**. 형식·유일성은 기존 `ErrorCodeStatusTest` 가 자동 검증하므로 별도 테스트 불필요
- [x] T003 [P] `common/src/main/kotlin/com/kbap/common/domain/CurrencyCode.kt` 상단에 환율 출처·기준 시점·단위 규약을 커밋 메시지가 아닌 **KDoc 아닌 형태로 남기지 않는다** — 대신 이 정보를 `specs/kb-322-member-currency/data-model.md` 에 기록한다(Kotlin 주석 전면 금지)
- [x] T004 [P] `common/src/test/kotlin/com/kbap/common/domain/CurrencyCodeTest.kt` **신설** — (a) 46종 전부 `krwPerUnit > 0`, (b) `from("KRW")` 은 KRW·`from("krw")`·`from(" KRW ")`·`from(null)` 은 null(정확 일치), (c) 1 미만 통화(VND·IDR)의 자릿수가 4자리로 보존됨(`BigDecimal("0.0544").scale() == 4`)
- [x] T005 [P] `common/src/test/kotlin/com/kbap/common/domain/member/model/CountryCodeTest.kt` **신설** — (a) 197개 전부 `currency` 를 갖는다(non-null 이므로 컴파일이 보장하지만 개수 197 을 고정), (b) `KR→KRW`·`JP→JPY`·`VN→VND`·`FR→EUR` 표본 검증, (c) **취급 통화 밖 국가는 USD 로 대체**됨을 표본으로 고정(예: `NG`(나이지리아)→USD, `AO`(앙골라)→USD)
- [x] T006 [P] `common/src/test/kotlin/com/kbap/common/domain/CurrencyRateSnapshotTest.kt` **신설** — 대표 환산이 기대치와 맞는지 고정한다: 9000원 → USD `9000 / 1416.0000 = 6.36`, JPY `9000 / 8.8906 = 1012.3`, VND `9000 / 0.0544 = 165,441`. **VND 를 반드시 포함**한다(2자리로 반올림했다면 8% 틀리므로 회귀 감지용)

**Checkpoint**: 통화 타입·매핑·에러 코드가 테스트로 고정됐다 — 스토리 시작 가능

---

## Phase 3: User Story 1 - 온보딩만 마치면 통화가 정해져 있다 (Priority: P1) 🎯 MVP

**Goal**: 온보딩에서 국가가 확정될 때 그 국가의 통화가 자동으로 저장된다. 사용자는 통화를 입력하지 않는다.

**Independent Test**: 국가를 지정해 온보딩한 뒤 프로필을 조회해 통화가 채워져 있는지 확인한다.

**의존**: Phase 2. US3(조회)이 있어야 API 로 확인 가능하지만, 도메인 테스트로는 독립 검증된다.

### Tests for User Story 1 (REQUIRED — 먼저 쓰고 FAIL 확인) ⚠️

- [x] T007 [US1] `common/src/test/kotlin/com/kbap/common/domain/member/model/MemberProfileTest.kt` 에 **온보딩 자동 지정 테스트** 추가 — `given("국가 JP 로 온보딩") / when("완료하면") / then("통화가 JPY 다")`. 현행 구현에서 FAIL
- [x] T008 [US1] 같은 파일에 **요청 통화 무시 테스트** 추가 — 온보딩 경로에는 통화 인자가 없으므로, `completeOnboarding` 시그니처에 통화 파라미터가 **없음**을 전제로 국가 기준 지정만 일어남을 고정

### Implementation for User Story 1

- [x] T009 [US1] `common/src/main/kotlin/com/kbap/common/domain/member/model/MemberProfile.kt` 에 `currency: CurrencyCode?` 필드 추가 — `of()`(hydration)·`empty()`(null) 갱신. `updatedWith` 는 T013 에서 다룬다
- [x] T010 [US1] `common/src/main/kotlin/com/kbap/common/domain/member/model/Member.kt` 에 `@Column(name = "currency", length = 3) var currency: String? = null` 추가하고, `profile` getter 가 `CurrencyCode.from(currency)` 로 되살리며 `updateProfile(profile)` 가 `currency = profile.currency?.name` 을 반영하게 한다
- [x] T011 [US1] 같은 파일 `Member.completeOnboarding` 에서 확정된 국가로부터 통화를 파생해 함께 저장한다(`CountryCode.from(countryCode)?.currency`). **정책은 도메인이 소유**한다 — 컨트롤러·서비스에서 매핑하지 않는다
- [x] T012 [US1] `./gradlew :common:test --tests "*MemberProfileTest*"` 로 T007·T008 Green 확인

**Checkpoint**: 온보딩을 마치면 통화가 저장된다

---

## Phase 4: User Story 2 - 국가와 다른 통화를 쓰는 사용자가 직접 바꾼다 (Priority: P1)

**Goal**: 프로필 수정에서 통화만 따로 바꾼다. **국가를 바꿔도 통화는 변하지 않는다**(FR-007, A안).

**Independent Test**: 통화만 바꿔 저장 후 유지되는지, 국가만 바꿨을 때 통화가 그대로인지 확인한다.

**의존**: Phase 2 + T009(필드 존재).

### Tests for User Story 2 (REQUIRED — 먼저 쓰고 FAIL 확인) ⚠️

- [x] T013 [P] [US2] `MemberProfileTest.kt` 에 **통화 변경 테스트** 추가 — 유효 값 교체 / 미전송 시 기존 유지 / 지원 목록 밖 값은 `BusinessException(INVALID_CURRENCY_CODE)` / 소문자·공백 값도 거절(정확 일치)
- [x] T014 [P] [US2] 같은 파일에 **FR-007 핵심 테스트** 추가 — `given("통화를 JPY 로 바꿔 둔 회원") / when("국가만 US 로 바꾸면") / then("국가는 US, 통화는 JPY 그대로")`. **이 테스트가 A안의 유일한 방어선**이다

### Implementation for User Story 2

- [x] T015 [US2] `MemberProfile.updatedWith` 에 `currency: String? = null` 파라미터 추가 — `currency?.let { validatedCurrency(it) } ?: this.currency`. **국가 인자와 서로 참조하지 않는다**(국가를 바꿔도 통화 식에 영향 없음 = FR-007)
- [x] T016 [US2] 같은 파일에 `private fun validatedCurrency(raw: String): CurrencyCode = CurrencyCode.from(raw) ?: throw BusinessException(ErrorCode.INVALID_CURRENCY_CODE)` 추가. **검증은 `updatedWith` 경유가 유일 경로**라는 기존 불변을 지킨다
- [x] T017 [US2] `Member.updateProfile(...)` 오버로드에 `currency: String? = null` 을 더해 `profile.updatedWith(currency = currency)` 로 전달
- [x] T018 [US2] `./gradlew :common:test --tests "*MemberProfileTest*"` 로 T013·T014 Green 확인

**Checkpoint**: 통화가 국가와 독립적으로 변경된다

---

## Phase 5: User Story 3 - 앱이 현재 통화를 읽을 수 있다 (Priority: P1)

**Goal**: 프로필 조회 응답에 통화가 담기고, 프로필 수정 요청으로 통화를 보낼 수 있다.

**Independent Test**: MockMvc 로 온보딩 → 조회 → 수정 → 재조회 흐름에서 통화가 기대대로 움직이는지 확인한다.

**의존**: Phase 3·4(도메인이 먼저 있어야 배선할 것이 있다).

### Tests for User Story 3 (REQUIRED — 먼저 쓰고 FAIL 확인) ⚠️

- [x] T019 [US3] `api/src/test/kotlin/com/kbap/api/member/MemberControllerTest.kt` 에 **API 시나리오 4건** 추가 — (a) 온보딩(국가 JP) 후 조회 시 `payload.currency == "JPY"`, (b) 통화만 PATCH 후 조회 시 반영, (c) 국가만 PATCH 후에도 통화 불변, (d) 지원 목록 밖 통화 PATCH 시 400 `MEMBER-010` 이고 기존 통화 유지. **기존 시나리오는 수정하지 않는다**

### Implementation for User Story 3

- [x] T020 [P] [US3] `common/src/main/kotlin/com/kbap/common/domain/member/dto/ProfileUpdateInput.kt` 에 `currency: String? = null` 추가, `common/.../dto/MyProfileResult.kt` 에 `currency: String?` 추가(엔티티 → 결과 매핑 포함)
- [x] T021 [P] [US3] `api/src/main/kotlin/com/kbap/api/member/ProfileUpdateRequest.kt` 에 `currency: String? = null` 추가하고 `toInput` 에 전달, `api/.../MyProfileResponse.kt` 에 `currency: String?` 추가하고 `from` 에 매핑
- [x] T022 [US3] `common/src/main/kotlin/com/kbap/common/domain/member/MemberService.updateProfile` 이 `input.currency` 를 엔티티로 전달하도록 배선
- [x] T023 [US3] `api/src/main/kotlin/com/kbap/api/member/MemberApi.kt` 의 swagger 에 통화 필드를 문서화 — 프로필 수정 요청(선택)·조회 응답. **온보딩 문서에는 통화를 넣지 않는다**(요청 필드가 아니다)
- [x] T024 [US3] `./gradlew :api:test --tests "*MemberControllerTest*"` 로 T019 Green + **기존 시나리오 무수정 통과** 확인

**Checkpoint**: 클라이언트가 통화를 읽고 바꿀 수 있다

---

## Phase 6: User Story 4 - 이미 가입한 회원도 통화를 갖는다 (Priority: P2)

**Goal**: 기존 회원의 통화가 각자의 국가 기준으로 채워진다.

**Independent Test**: 마이그레이션 적용 후 `country_code IS NOT NULL AND currency IS NULL` 인 행이 0건인지 확인한다.

**의존**: T010(컬럼 정의)과 같은 이름·타입이어야 한다.

### Tests for User Story 4 (REQUIRED) ⚠️

- [x] T025 [US4] `api/src/test/kotlin/com/kbap/api/member/CurrencyBackfillSyncTest.kt` **신설** — 마이그레이션 SQL 을 리소스로 읽어 `CASE` 분기의 (국가, 통화) 쌍 집합이 `CountryCode` 전수와 **정확히 일치**하는지 검증한다(`IngredientCatalogSeedSyncTest` 와 같은 방식). 테스트 설명에 **버전 번호를 박지 않는다**

### Implementation for User Story 4

- [x] T026 [US4] `api/src/main/resources/db/migration/V2026.08.11.<HH.mm.ss>__member_currency.sql` 신설 — `ALTER TABLE member ADD COLUMN currency varchar(3) NULL;` + `UPDATE member SET currency = CASE country_code WHEN 'KR' THEN 'KRW' ... END WHERE country_code IS NOT NULL;`. **197개 분기 전수**. 버전은 파일 생성 시각(점 구분 timestamp)
- [x] T027 [US4] `./gradlew :api:test` 로 T025 Green + Testcontainers MySQL 에서 마이그레이션이 실제로 적용되는지 확인(`ddl-auto=validate` 가 엔티티↔스키마 정합을 함께 본다)

**Checkpoint**: 기존 회원도 통화를 갖는다

---

## Phase 7: Polish & 문서 정합

**Purpose**: 낡은 설계 문서를 실제 산출물에 맞추고 전체를 검증한다

- [x] T028 [P] `specs/kb-322-member-currency/spec.md` 갱신 — FR-004 를 "197개 국가 각각이 통화를 갖는다(취급 통화 46종, 그 외 국가는 USD 대체)"로 고치고, Assumptions 의 "지원 통화 범위는 국가 매핑에 등장하는 통화" 를 실제(지정 45 + KRW)로 교체. SC-002 도 함께 조정
- [x] T029 [P] `specs/kb-322-member-currency/research.md` 갱신 — R3(매핑 위치)에 **환율은 `CurrencyCode` 소유**(유로존 20개국 중복 방지) 결정을 추가하고, 신규 R9(환율 출처를 frankfurter → 은행 고시 매매기준율로 교체한 경위·이유)·R10(1단위 정규화와 4자리 자릿수 결정) 추가
- [x] T030 [P] `specs/kb-322-member-currency/data-model.md` 갱신 — 통화 46종·`krwPerUnit` non-null·4자리·USD 대체 117개국·환율 기준 시점을 반영. **환율 출처와 스냅샷 시점을 여기 기록한다**(코드 주석 금지 규약)
- [x] T031 `./gradlew build` 전체 통과 확인 (MySQL Testcontainers — Docker 필요)
- [x] T032 [P] `../kbap-agenthub/wiki/` 에 "회원 통화 — 국가 자동 지정, 이후 독립" 항목을 기록하고 `INDEX.md` 에 한 줄 추가 — 코드로 알 수 없는 것 위주: FR-007 A안 선택 근거, 환율이 **고정 스냅샷**이라 시간이 지나면 벌어진다는 점, 취급 통화 밖 국가를 USD 로 대체한 결정

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: 의존 없음
- **Phase 2 (Foundational)**: T001 이후. **US1~US4 전부를 블록**한다
- **Phase 3 (US1)**: T002~T006 이후
- **Phase 4 (US2)**: T009(필드 존재) 이후 — T013·T014 는 US1 구현과 **병렬 가능**
- **Phase 5 (US3)**: Phase 3·4 이후(도메인이 있어야 배선)
- **Phase 6 (US4)**: T010(컬럼 정의) 이후 — Phase 5 와 **병렬 가능**
- **Phase 7 (Polish)**: 전부 완료 후

### 파일 충돌 맵 (병렬 판단 근거)

| 파일 | 건드리는 task |
|------|---------------|
| `MemberProfile.kt` | T009, T015, T016 → **순차** |
| `Member.kt` | T010, T011, T017 → **순차** |
| `MemberProfileTest.kt` | T007, T008, T013, T014 → **순차**(한 번에 몰아 쓰기 권장) |
| `MemberControllerTest.kt` | T019 |
| DTO 4종 | T020, T021 → 서로 다른 파일이라 병렬 |
| 마이그레이션·백필 테스트 | T025, T026 |
| spec·research·data-model | T028, T029, T030 → 병렬 |

### Parallel Opportunities

```bash
# Phase 2 내부: T004·T005·T006 은 각각 다른 신규 테스트 파일이라 동시 작성 가능
# Phase 3~4: 도메인 테스트를 한 번에 몰아 쓰고(T007·T008·T013·T014) Red 를 한 번에 확인
# Phase 5~6: US3(API 배선)과 US4(마이그레이션)는 파일이 겹치지 않아 병렬
# Phase 7: T028·T029·T030 문서 3종 병렬
```

---

## Implementation Strategy

### MVP (US1+US2+US3 가 최소 배포 단위)

US1 만으로는 통화가 저장돼도 **읽거나 고칠 수 없다**. US3(조회·수정 배선)까지 있어야 화면에서 확인 가능하고, US2 가 없으면 어긋난 사용자가 영영 고칠 수 없다. 셋을 한 릴리스로 본다.

US4(백필)는 분리 가능하지만, 없으면 기존 회원만 통화가 비어 후속 KB-323 이 그들에게 동작하지 않는다 — 같이 가는 것을 권한다.

### 권장 순서 (1인 작업)

1. T001 → T002 → T004·T005·T006 몰아 쓰기 → Red 확인
2. T007·T008·T013·T014 몰아 쓰기 → **도메인 Red 한 번에 확인**
3. T009 → T010 → T011 → T015 → T016 → T017 → Green
4. T019 → Red → T020~T023 → T024 Green
5. T025 → Red → T026 → T027 Green
6. T028~T030 문서 → T031 build → T032 위키

### Commit 단위

- T002~T006 (통화 타입 테스트 — 이미 들어간 enum 의 사후 방어)
- T007~T018 (도메인: 온보딩 자동 지정 + 개별 변경)
- T019~T024 (API 배선)
- T025~T027 (마이그레이션·백필)
- T028~T032 (문서·위키)

---

## Notes

- **Kotlin 주석 전면 금지**(2026-08-11 지시) — 환율 출처·기준 시점·단위 규약 같은 맥락은 **코드가 아니라 `data-model.md` 와 커밋 메시지**에 남긴다
- 환율은 **고정 스냅샷**이다. 실시간 갱신 경로를 이 티켓에서 만들지 않는다(KB-323 범위)
- `krwPerUnit` 은 **1단위 기준**이다. 은행 고시의 `100엔`·`100루피아`·`100동` 표기를 100으로 나눠 정규화했으므로, 새 통화를 추가할 때 같은 규칙을 지킨다
- Red 확인은 "테스트를 썼다"가 아니라 **실행해서 빨간 것을 봤다**를 의미한다(헌법 원칙 I)
- 마이그레이션 파일명을 바꾸면 T025 의 리소스 경로도 함께 바꾼다 — 안 그러면 "파일 없음"이 아니라 **데이터 불일치**로 조용히 깨진다

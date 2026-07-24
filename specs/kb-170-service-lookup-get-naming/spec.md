# Feature Specification: 서비스 조회 메서드 네이밍 get 통일

**Feature Branch**: `kb-170-service-lookup-get-naming`

**Created**: 2026-07-18

**Status**: Draft

**Input**: User description: "kb-170"

## User Scenarios & Testing *(mandatory)*

이 기능의 "사용자"는 백엔드 코드를 읽고 유지보수하는 개발자다. 조회 메서드 이름만으로 "없을 때 예외인지 null 인지, 단건인지 목록/페이지인지"를 예측할 수 있게 만드는 것이 목적이며, **외부로 노출되는 API 동작·응답은 바뀌지 않는다**(순수 리팩터링).

### User Story 1 - 조회 메서드 이름으로 계약을 예측한다 (Priority: P1)

개발자가 도메인·애플리케이션 서비스의 조회 메서드를 호출할 때, 접두어(`get~`)만 보고도 "대상이 없으면 예외를 던지고 non-null 을 반환한다"는 계약을 즉시 알 수 있다. `find`/`get` 혼용으로 인한 오독이 사라진다.

**Why this priority**: 이 통일이 기능의 본체다. 나머지 스토리는 이 규칙을 문서·경계로 고정하는 보조 작업이다.

**Independent Test**: 리네임 후 전체 테스트(`./gradlew test`)가 통과하고, 서비스 public 조회 메서드에 `find` 접두가 남아 있지 않음을 확인하면(단, null 이 정상값인 `get~OrNull`·유비쿼터스 동사 제외) 검증된다.

**Acceptance Scenarios**:

1. **Given** 리네임이 적용된 상태, **When** 개발자가 단건 조회 메서드를 찾으면, **Then** 없으면 예외를 던지는 조회는 `get~`, null 이 정상값인 조회는 `get~OrNull` 로만 존재한다.
2. **Given** 리네임이 적용된 상태, **When** 페이지를 반환하는 조회 메서드를 보면, **Then** 이름은 `get~Page` 이고 반환 타입도 `~Page` 로 일치한다.
3. **Given** 기존 API 클라이언트, **When** 리네임 전후로 동일한 요청을 보내면, **Then** 응답 본문·에러 코드·HTTP 상태가 동일하다(동작 무변경).

---

### User Story 2 - 네이밍 규약이 문서로 고정된다 (Priority: P2)

개발자가 새 조회 메서드를 추가할 때, 프로젝트 규약 문서(CLAUDE.md)를 보고 `get~`/`get~OrNull`/`get~Page` 중 무엇을 쓸지 판단할 수 있다.

**Why this priority**: 규약이 문서화되지 않으면 시간이 지나며 다시 `find`/`get` 혼용으로 되돌아간다.

**Independent Test**: CLAUDE.md 의 서비스 메서드 네이밍 규약을 읽고, get 통일 + `get~OrNull` 예외 조건 + 규약 밖(유비쿼터스 동사·보조·행위) 구분이 명시돼 있는지 확인한다.

**Acceptance Scenarios**:

1. **Given** 갱신된 규약 문서, **When** 개발자가 컬렉션 조회 메서드를 추가하면, **Then** 문서가 `get~s`(빈 값 허용) 형태를 안내한다.
2. **Given** 갱신된 규약 문서, **When** 유비쿼터스 동사(`search`·`findOrSignUp`)를 마주하면, **Then** 규약 밖 예외임이 문서로 확인된다.

---

### Edge Cases

- **null 이 도메인상 정상값인 조회**(게스트 회원 조회, refresh 시 회원 부재): `get~` 로 통일하면 예외가 정상 흐름을 깨뜨린다 → `get~OrNull` 로 남겨 호출부가 null 을 분기한다.
- **에러 코드가 호출부마다 다른 경우**(refresh 부재=INVALID_REFRESH_TOKEN vs withdraw 부재=MEMBER_NOT_FOUND): 단일 throw 메서드로 합칠 수 없으므로 `get~OrNull` + 호출부 판단을 유지한다.
- **이름에 `Page` 가 붙었으나 List 를 반환하던 내부 로더**: `Page` 접미를 제거하고 컬렉션 규칙(`get~s`, internal)으로 흡수해 이름·타입 불일치를 해소한다.
- **동작 변경을 수반하는 조회**(`findReadyById`→`getReadyFood`): 두 호출부가 이미 `?: throw FOOD_NOT_FOUND` 하던 코드라, 예외를 메서드 내부로 옮겨도 최종 동작은 동일하다.
- **`findVerifiedImage` 의 재분류**: 이 메서드는 "경로의 이미지가 (a) 업로드 검증을 통과해 기록돼 있고 (b) 요청 회원 소유인가"를 확인하는 검증 로직이다. 조회(get/find)가 아니라 **검증 행위**이므로 `verifyImageAccess` 로 재명명해 get/find 규약 밖으로 둔다. ScanService 의 소유 검증 배선(현재 TODO)은 **이번 작업 범위 밖**으로 주석·미사용 상태를 유지한다(순수 리네임, 동작 무변경).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 서비스의 단건 조회 메서드는 대상이 없을 때 `BusinessException` 을 던지고 non-null 을 반환하는 경우 `get~` 로 명명해야 한다.
- **FR-002**: null 이 도메인상 정상값인 단건 조회 메서드만 `get~OrNull`(반환 `T?`)로 명명해야 하며, 그 외 `find` 접두는 폐기한다.
- **FR-003**: 컬렉션 조회 메서드는 빈 값을 허용하며 `get~`(예: `get~s`)로 명명해야 한다.
- **FR-004**: 페이지 조회 메서드는 이름을 `get~Page` 로 하고 반환 타입도 `~Page` 로 일치시켜야 한다. List 를 반환하던 내부 로더는 `Page` 접미를 제거한다.
- **FR-005**: 유비쿼터스 동사(`search`·`findOrSignUp`)·보조(`next~`·`count~`·`is/has/exists`)·행위(CRUD·도메인 동사)는 이 규약 밖으로 유지한다.
- **FR-006**: 리네임은 외부 API 계약(요청·응답 본문·에러 코드·HTTP 상태)을 변경하지 않아야 한다.
- **FR-007**: 모든 호출부(도메인·애플리케이션·컨트롤러·테스트)가 새 이름을 사용하도록 함께 갱신되어야 하며, 전체 테스트가 통과해야 한다.
- **FR-008**: 프로젝트 규약 문서(CLAUDE.md)의 서비스 메서드 네이밍 항목이 get 통일 규칙과 `get~OrNull` 예외 조건을 반영해야 한다.
- **FR-009**: `MemberService` 의 회원 부재가 정상값인 조회는 `getMemberOrNull` 로, 예외가 정상인 조회는 public `getMember` 로 분리 제공해야 한다.
- **FR-010**: `ImageUploadService.findVerifiedImage` 는 조회가 아닌 검증 행위로 재분류해 `verifyImageAccess` 로 명명하고 get/find 규약 밖으로 둔다. ScanService 의 소유 검증 배선(TODO)은 이번 범위 밖으로 미사용·주석 상태를 유지한다.

### Key Entities

해당 없음 — 데이터 모델·스키마 변경 없음(순수 메서드 네이밍 리팩터링).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 리네임 후 전체 테스트(`./gradlew test`)가 100% 통과한다.
- **SC-002**: 서비스 public 조회 메서드 중 `find` 접두를 가진 메서드가 0개다(유비쿼터스 동사 `findOrSignUp` 제외).
- **SC-003**: 페이지 조회 메서드의 이름(`~Page`)과 반환 타입(`~Page`) 불일치가 0건이다.
- **SC-004**: 리네임 전후로 동일 요청에 대한 API 응답(본문·에러 코드·상태)이 100% 동일하다.

## Assumptions

- 이 작업은 순수 리팩터링이며 새 API·DB 스키마·마이그레이션을 추가하지 않는다.
- `:app:batch` 및 배치 전용 조회(`nextChunk`)는 규약 밖으로 유지한다.
- `getDetail`→`getFoodDetail` 명확화 리네임은 선택 사항으로, 필수 완료 기준에는 포함하지 않는다.
- 이미 규약을 준수하는 메서드(`getMyProfile`·`getRanking`·`getDetail`·`getHome`·`getAvoidedCodes`)는 변경하지 않는다.
- KB-170 이슈 본문 표에는 `findVerifiedImage`→`getVerifiedImage` 로 적혀 있으나, 논의 결과 검증 행위 `verifyImageAccess` 로 재분류하기로 확정했다(이 명세가 우선).

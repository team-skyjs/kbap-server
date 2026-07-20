# Feature Specification: 내 프로필 조회 응답에 소셜 로그인 연동 정보(provider) 추가

**Feature Branch**: `kb-191-profile-provider`

**Created**: 2026-07-20

**Status**: Draft

**Input**: User description: "KB-191 — 내 프로필 조회 응답에 소셜 로그인 연동 정보(provider) 추가 (워크트리로 진행)"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 설정 화면에서 연동된 소셜 계정 종류 확인 (Priority: P1)

로그인한 사용자가 내 프로필을 조회하면, 자신이 어떤 소셜 계정(GOOGLE 또는 APPLE)으로 가입·연동되어 있는지 응답에서 확인할 수 있다. 클라이언트는 이 값으로 설정 화면 등에 "Google 계정으로 로그인됨" 같은 연동 정보를 표시한다.

**Why this priority**: 이 기능의 유일한 요구사항이다. 서비스는 이미 회원별 소셜 제공자를 저장하고 있으나 프로필 응답에 노출하지 않아 클라이언트가 표시할 수 없다.

**Independent Test**: 소셜 계정으로 가입한 회원이 내 프로필 조회 API 를 호출해 응답에 가입 시 사용한 제공자 값이 그대로 포함되는지 확인하면 전체 기능이 검증된다.

**Acceptance Scenarios**:

1. **Given** GOOGLE 계정으로 가입한 회원이 로그인한 상태, **When** 내 프로필을 조회하면, **Then** 응답에 `provider` 필드가 `GOOGLE` 값으로 포함된다.
2. **Given** APPLE 계정으로 가입한 회원이 로그인한 상태, **When** 내 프로필을 조회하면, **Then** 응답에 `provider` 필드가 `APPLE` 값으로 포함된다.
3. **Given** 기존 프로필 응답을 사용하는 클라이언트, **When** 내 프로필을 조회하면, **Then** 기존 필드(닉네임·기피물질·국가/언어·프로필 이미지·맵기 선호·온보딩 여부·랭킹)는 값과 형태가 변하지 않는다.

### Edge Cases

- 모든 회원은 소셜 계정으로만 가입하므로(저장값 필수) provider 가 비어 있는 회원은 존재하지 않는다 — 응답 필드도 항상 값을 가진다.
- 탈퇴(비활성) 회원은 프로필 조회 자체가 기존 규칙대로 거부되므로 이 기능의 추가 분기가 없다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 내 프로필 조회 응답은 회원이 가입 시 연동한 소셜 제공자 식별 값(`provider`)을 포함해야 한다.
- **FR-002**: `provider` 값은 저장된 값(GOOGLE / APPLE)을 가공 없이 그대로 노출해야 한다.
- **FR-003**: 기존 프로필 응답의 다른 필드는 이름·값·구조 모두 변경되지 않아야 한다(하위 호환).
- **FR-004**: API 문서(Swagger)에 `provider` 필드가 반영되어야 한다.

### Key Entities

- **회원(Member)**: 소셜 제공자(provider: GOOGLE/APPLE)와 제공자별 식별자를 이미 보유. 본 기능은 저장 구조를 바꾸지 않고 조회 응답에만 노출한다.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 로그인한 회원 100% 가 내 프로필 조회 한 번으로 자신의 연동 소셜 계정 종류를 확인할 수 있다.
- **SC-002**: 기존 프로필 조회 클라이언트는 수정 없이 동일하게 동작한다(기존 필드 회귀 0건).
- **SC-003**: 신규 API·데이터 저장 구조 변경 없이 기존 조회 흐름만으로 제공된다.

## Assumptions

- provider 저장값은 모든 회원에 필수로 존재한다(소셜 가입만 지원) — 응답 필드는 항상 non-null.
- 노출 값은 저장 enum 이름(GOOGLE/APPLE) 그대로이며, 표시 문구 변환(예: "Google")은 클라이언트 책임이다.
- 신규 API·DB 스키마 변경·마이그레이션은 없다. 변경 범위는 내 프로필 조회 응답 조립뿐이다.

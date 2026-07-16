# Feature Specification: 프로필 이미지 업로드 purpose 코드(PROFILE_IMAGE) 추가

**Feature Branch**: `kb-164-profile-image-purpose`

**Created**: 2026-07-17

**Status**: Draft

**Input**: User description: "kb-164 진행 — [BE] presigned URL 발급 API 에 프로필 이미지 purpose 코드(PROFILE_IMAGE) 추가"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 프로필 이미지 업로드 URL 발급 (Priority: P1)

앱 사용자가 프로필 사진을 등록/변경하려 할 때, 클라이언트는 이미지 업로드 URL 발급 API 에 용도 코드 `PROFILE_IMAGE` 를 지정해 요청한다. 시스템은 프로필 이미지 전용 저장 폴더(`profile`)를 가리키는 업로드 URL 과 공개 URL 을 발급한다. 메뉴판 스캔 이미지(`scan` 폴더)와 저장 위치가 분리되어 용도별 관리(수명주기·정리·통계)가 가능하다.

**Why this priority**: 이 스토리가 기능의 전부다 — `PROFILE_IMAGE` 용도가 없으면 프로필 이미지 업로드 기능 자체를 클라이언트가 만들 수 없다.

**Independent Test**: 인증된 사용자로 업로드 URL 발급 API 에 `purpose=PROFILE_IMAGE` 를 보내 발급이 성공하고, 발급된 객체 키(저장 경로)가 `images/profile/...` 형태인지 확인하면 완결적으로 검증된다.

**Acceptance Scenarios**:

1. **Given** 인증된 사용자, **When** `PROFILE_IMAGE` 용도로 허용 형식·허용 크기의 이미지 업로드 URL 을 요청하면, **Then** 발급이 성공하고 저장 경로가 `images/profile/...` 형태로 생성된다 (메뉴판 스캔의 `images/scan/...` 과 폴더가 다르다).
2. **Given** 인증된 사용자, **When** 기존 용도(`MENU_SCAN`·`REVIEW`)로 요청하면, **Then** 기존과 동일하게 각 용도의 폴더로 발급된다 (기존 동작 무변경).
3. **Given** 인증된 사용자, **When** 지원하지 않는 용도 문자열로 요청하면, **Then** 기존과 동일하게 용도 오류(UPLOAD-002)로 거절된다.

---

### Edge Cases

- 용도 코드는 정확한 값 매칭이다 — `profile_image`(소문자)·`PROFILE`(축약) 등 불일치 값은 기존 규칙대로 용도 오류로 거절된다.
- `PROFILE_IMAGE` 용도라도 형식(UPLOAD-001)·크기(UPLOAD-003) 검증은 기존 용도와 동일하게 적용된다 — 용도별 별도 정책은 없다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 시스템은 이미지 업로드 URL 발급 시 용도 코드 `PROFILE_IMAGE` 를 허용해야 한다.
- **FR-002**: `PROFILE_IMAGE` 용도로 발급된 이미지의 저장 경로는 전용 폴더 `profile` 아래(`images/profile/...`)로 생성되어, 메뉴판 스캔(`scan`)·리뷰(`review`) 폴더와 분리되어야 한다.
- **FR-003**: 기존 용도(`MENU_SCAN`·`REVIEW`)의 발급 동작·저장 경로는 변경되지 않아야 한다.
- **FR-004**: API 문서의 용도 허용값 안내에 `PROFILE_IMAGE` 가 반영되어야 한다.

### Key Entities

- **업로드 용도(UploadPurpose)**: 업로드 이미지의 쓰임새를 나타내는 코드. 각 용도는 저장 폴더 접두어를 하나씩 가진다 — 기존 `MENU_SCAN`→`scan`, `REVIEW`→`review` 에 `PROFILE_IMAGE`→`profile` 이 추가된다.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 클라이언트가 `PROFILE_IMAGE` 용도로 업로드 URL 발급을 요청하면 100% 성공적으로 발급된다 (형식·크기 정책 위반 제외).
- **SC-002**: `PROFILE_IMAGE` 로 업로드된 모든 이미지의 저장 경로가 `images/profile/` 아래에 생성된다 — 메뉴판 스캔 폴더와 겹치는 경우 0건.
- **SC-003**: 기존 용도(`MENU_SCAN`·`REVIEW`)의 발급 성공률·저장 경로에 회귀 0건.
- **SC-004**: API 문서만 보고 클라이언트 개발자가 프로필 이미지 업로드 용도 코드를 추가 문의 없이 파악할 수 있다.

## Assumptions

- 전용 폴더 접두어는 Jira 예시대로 `profile` 로 한다.
- 허용 형식·최대 크기·URL 유효시간 등 발급 정책은 용도와 무관한 공통 정책(KB-145)을 그대로 쓴다 — 프로필 이미지 전용 정책(예: 더 작은 크기 제한, 이미지 리사이즈)은 범위 밖.
- 발급된 URL 로 실제 업로드 후 완료 신고(KB-138 `POST /api/v1/images/complete`)·회원 프로필에 이미지 URL 을 저장하는 API 는 이 기능의 범위 밖이다 (별도 이슈).
- 데이터베이스·저장소 인프라 변경 없음 — 용도 코드와 경로 규칙만 추가된다.

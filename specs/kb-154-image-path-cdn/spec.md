# Feature Specification: 이미지 참조는 CDN 도메인 없이 경로만 저장하고 응답 조립 시 조합

**Feature Branch**: `kb-154-image-path-cdn`

**Created**: 2026-07-18

**Status**: Draft

**Input**: User description: "kb-154 워크트리로 시작. 이 태스크에 적힌 내용이 이미 구현되어있는지 아닌지를 모르겠네. 확인후 진행여부를 결정내려줘" (Jira KB-154)

## 사전 조사 — 구현 여부 판정 (2026-07-18)

| 대상 | 현재 상태 | 판정 |
|---|---|---|
| 프로필 사진 | `MemberProfile.profileImageUrl` 에 **전체 URL 저장** + 허용 호스트 검증(`PROFILE_IMAGE_ALLOWED_HOSTS`) | ❌ 미구현 — 티켓이 지적하는 구조 그대로 |
| 음식 이미지 | `Food.imageRef` 저장값을 응답까지 그대로 passthrough, CDN 조합 없음 | ❌ 미구현 |
| 스캔 이미지 | `scan_history.image_path` 경로만 저장, 요청도 전체 URL 거부(KB-138) | ✅ 이미 준수 — 범위 밖 |
| CDN 프로퍼티 | `kbap.image-upload.public-base-url` 환경별 주입 인프라 존재(KB-145) | ✅ 재사용 |

**결론: 핵심 2대상(프로필 사진·음식 이미지)이 미구현이므로 진행한다.**

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 프로필 사진 참조를 경로로 저장하고 완전한 URL 로 응답 (Priority: P1)

회원이 프로필 사진을 등록·수정하면 시스템은 이미지 참조를 CDN 도메인 없는 경로(키)로 저장하고, 프로필 조회 응답에서는 설정된 CDN 도메인이 조합된 완전한 URL 을 내려준다.

**Why this priority**: 현재 유일하게 전체 URL 이 DB 에 저장되는 경로다. CDN 도메인 교체 시 회원 행 전체를 수정해야 하는 마이그레이션 리스크가 실제로 존재한다.

**Independent Test**: 온보딩/프로필 수정으로 사진 경로를 저장한 뒤 DB 저장값(경로만)과 프로필 조회 응답값(완전한 URL)을 각각 검증.

**Acceptance Scenarios**:

1. **Given** 인증된 회원, **When** 프로필 사진을 경로(키)로 등록하면, **Then** DB 에는 CDN 도메인 없이 경로만 저장된다
2. **Given** 사진이 등록된 회원, **When** 프로필을 조회하면, **Then** 응답의 사진 URL 은 설정된 CDN 도메인 + 경로가 조합된 완전한 URL 이다
3. **Given** 인증된 회원, **When** 전체 URL(`http(s)://…`) 형태로 사진을 등록하려 하면, **Then** 요청이 거부된다
4. **Given** 사진 미등록 회원, **When** 프로필을 조회하면, **Then** 사진 URL 은 null 이다

---

### User Story 2 - 음식 이미지 참조를 완전한 URL 로 응답 (Priority: P2)

음식 상세·목록·검색 응답의 음식 이미지가 저장된 경로에 CDN 도메인이 조합된 완전한 URL 로 내려간다.

**Why this priority**: 저장 컬럼(`image_ref`)은 이미 참조 형태지만 응답 조립이 없어, 클라이언트가 쓸 수 있는 URL 이 되려면 조합이 필요하다.

**Independent Test**: 음식 시드에 경로형 이미지 참조를 넣고 상세/목록 응답의 이미지 값이 CDN 도메인이 조합된 완전한 URL 인지 검증.

**Acceptance Scenarios**:

1. **Given** 이미지 경로가 저장된 음식, **When** 음식 상세/목록을 조회하면, **Then** 이미지 값은 CDN 도메인이 조합된 완전한 URL 이다
2. **Given** 이미지가 없는 음식, **When** 조회하면, **Then** 이미지 값은 null 이다

---

### User Story 3 - CDN 도메인 교체는 설정 변경만으로 완료 (Priority: P3)

운영자가 CDN 도메인을 교체할 때 DB 는 손대지 않고 환경 설정 값 하나만 바꾸면 이후 모든 응답이 새 도메인으로 내려간다.

**Why this priority**: 이 태스크의 존재 이유(마이그레이션 참사 방지)지만, US1·US2 가 구현되면 구조적으로 따라오는 성질이다.

**Independent Test**: 테스트 설정의 CDN 베이스 값을 바꾸면 동일 저장 데이터에 대한 응답 URL 도메인이 바뀌는지 검증.

**Acceptance Scenarios**:

1. **Given** 경로로 저장된 이미지 참조, **When** CDN 도메인 설정을 교체하면, **Then** DB 변경 없이 응답 URL 이 새 도메인으로 조합된다

---

### Edge Cases

- 저장된 참조가 이미 절대 URL(`http(s)://…`)인 레거시 행 → 조립 시 도메인을 덧붙이지 않고 그대로 반환(하위호환 통과)
- CDN 도메인이 설정되지 않은 환경(local 기본) → 저장된 경로를 그대로 반환
- 경로 선두 `/` 유무 → 조합 결과에 `//` 가 생기지 않게 정규화
- 사진 제거(빈 문자열) 규약(KB-124 3분법: null=유지·값=교체·빈 문자열=제거) → 의미 불변

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 프로필 사진 참조는 DB 에 CDN 도메인 없는 경로(키)만 저장해야 한다
- **FR-002**: 프로필 사진 등록·수정 입력에서 전체 URL(`http(s)://` 시작)은 거부해야 한다 (기존 허용 호스트 검증은 경로 검증으로 대체)
- **FR-003**: 프로필·음식 응답 조립 시 저장된 경로에 설정된 CDN 도메인을 조합해 완전한 URL 로 내려줘야 한다
- **FR-004**: CDN 도메인은 환경 설정 프로퍼티로 관리하고 환경별(dev·staging·prod)로 주입해야 한다
- **FR-005**: 이미지 참조가 없으면 응답값은 null 이어야 한다
- **FR-006**: 저장값이 절대 URL 인 레거시 데이터는 조립 시 그대로 반환해야 한다(이중 도메인 금지)
- **FR-007**: 통합 테스트로 저장값(경로만)과 응답값(완전한 URL)을 각각 검증해야 한다

### Key Entities

- **회원 프로필**: 프로필 사진 참조 — 전체 URL 저장에서 경로(키) 저장으로 전환되는 대상
- **음식**: 이미지 참조(`image_ref`) — 이미 참조 형태, 응답 조립만 추가
- **CDN 베이스 설정**: 환경별 주입되는 공개 이미지 도메인 — 기존 `public-base-url`(KB-145) 재사용

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 신규 저장되는 이미지 참조 중 CDN 도메인을 포함한 행이 0건이다
- **SC-002**: 이미지가 있는 프로필·음식 조회 응답의 이미지 값이 100% 완전한 URL(설정 도메인 + 경로)이다
- **SC-003**: CDN 도메인 교체가 DB 수정 0건, 설정 변경 1곳으로 완료된다
- **SC-004**: 기존 클라이언트 표시 동작(레거시 절대 URL 행 포함) 회귀 0건

## Assumptions

- CDN 베이스는 KB-145 의 `kbap.storage.public-base-url` 프로퍼티(환경별 `IMAGE_PUBLIC_BASE_URL`)를 재사용한다 — 신규 프로퍼티를 만들지 않는다
- 클라이언트는 presigned 발급 응답(KB-145)에서 objectKey 를 이미 받으므로 사진 등록 시 경로 전송이 가능하다 (클라이언트 계약 변경은 입력 형식: 전체 URL → 경로)
- 기존 DB 의 절대 URL 프로필 행은 데이터 마이그레이션 없이 조립 시 통과(FR-006)로 호환한다 — Flyway 변경 0건
- 스캔 이미지(`image_path`)는 이미 경로 저장·응답 미노출이므로 범위 밖
- 신규 API 는 없다 — 기존 응답 필드의 값 의미(경로→완전 URL 조합)만 바뀐다

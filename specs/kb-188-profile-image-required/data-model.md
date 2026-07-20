# Data Model: 프로필 사진 필수화 (KB-188)

DB 스키마(DDL) 변경 없음 — `member.profile` JSON 컬럼 내 `profileImageUrl` 키의 **값 계약**과 도메인 값 객체의 검증 규칙만 바뀐다.

## MemberProfile (값 객체, `:domain:member`)

| 필드 | 타입 | 변경 | 규칙 (변경 후) |
|------|------|------|----------------|
| `profileImageUrl` | `String?` | 검증 규칙 변경 | 값이 전달되면: trim 후 **빈 문자열 → MEMBER-008 throw**(기존: null 반환 = 제거 센티널), `http(s)://` 시작 → MEMBER-008, 512자 초과 → MEMBER-008. 통과 시 trim 된 경로 저장 |

- 필드 타입은 `String?` 유지 — **온보딩 전 회원**(`signUp` 직후, `MemberProfileJson()` 기본값)은 구조적으로 null 이며 이는 계약 위반이 아니다. non-null 계약의 대상은 "온보딩 완료 회원의 저장값"이다.
- `updatedWith` 의 사진 처리: 3분법(null=유지 · 값=교체 · 빈 문자열=제거) → **2분법**(null=유지 · 값=검증 후 교체).
- `validatedImagePath` 반환 타입: `String?` → `String`.

## 온보딩 입력 체인 (non-null 전파)

| 지점 | 변경 |
|------|------|
| `OnboardingRequest.profileImageUrl` (`:app:api`) | `String? = null` → `String` (미전송/null → 역직렬화 실패 → 400 COMMON-002) |
| `MemberProfileInput.profileImageUrl` (`:domain:member`) | `String? = null` → `String` |
| `Member.completeOnboarding(profileImageUrl)` | `String?` → `String` |

프로필 수정 체인(`ProfileUpdateRequest`·`ProfileUpdateInput`·`Member.updateProfile`)은 nullable 유지 — null=유지 부분 수정 규약(KB-124) 불변.

## MemberProfileJson (영속 JSON, `:domain:member`)

변경 없음 — `profileImageUrl: String? = null` 유지(온보딩 전 행·역직렬화 방어). 백필 후 온보딩 완료 행은 전부 non-null 값을 가진다.

## 백필 (Flyway, `:app:api` 스키마 owner)

| 항목 | 값 |
|------|-----|
| 파일 | `V<생성시각 timestamp>__backfill_default_profile_image.sql` |
| 대상 | `profile` JSON 의 `profileImageUrl` 키가 **부재하거나 JSON null** 인 전 행(소프트 삭제 포함 — status 필터 없음) |
| 값 | `/images/default/profile/profile-default-512.png` |
| 성질 | 단독 1문·순서 독립·멱등(WHERE 가드) — 기존 값 보유 행 무변경 |

## 상태 전이 (회원 프로필 사진 관점)

```text
가입(signUp)            : profileImageUrl = null            (온보딩 전 — 유일한 null 상태)
온보딩(completeOnboarding): 반드시 경로 존재 (기본 이미지 경로 포함) — null·빈 문자열 진입 불가
수정(updateProfile)      : null=유지 / 유효 경로=교체 — 빈 문자열 진입 불가 (제거 상태로 되돌아갈 수 없음)
백필(migration)          : 기존 null 행 → 기본 이미지 경로 (1회성)
```

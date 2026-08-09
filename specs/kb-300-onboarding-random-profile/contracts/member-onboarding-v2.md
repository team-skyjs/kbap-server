# Contract: 온보딩 API v1 / v2

## 신규 — `POST /api/v2/members/me/onboarding`

인증: `Authorization: Bearer {accessToken}` (필수)

### Request

```json
{
  "avoidanceSubstanceCodes": ["EGG", "MILK"],
  "countryCode": "US",
  "spicinessPreference": "SKIP"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `avoidanceSubstanceCodes` | `string[]` | 선택 (기본 `[]`) | 기피 성분 코드. 카탈로그에 없는 코드는 400 `MEMBER-005` |
| `countryCode` | `string` | **필수** | ISO 2자리. 미지원 코드는 400 `MEMBER-006` |
| `spicinessPreference` | `string` | **필수** | `SKIP`·`NONE`·`MILD`·`MEDIUM`·`HOT`·`EXTREME`. 그 외는 400 `MEMBER-009` |

**닉네임·프로필 사진 필드는 받지 않는다.** `nickname`·`profileImageUrl` 을 함께 보내도 알 수 없는 필드로 **무시**되며 오류가 아니다(v2 프로필 수정의 `countryCode` 무시와 동일). 닉네임은 서버가 영숫자 6자 코드(예: `K7M2XB`)로 생성하고, 프로필 사진은 아바타 6종 후보에서 무작위로 고른다.

필수 필드를 누락하면 Jackson 역직렬화 실패 → 400 `COMMON-002`(기존 `GlobalExceptionHandler` 경로, v1 과 동일).

### Response

성공 `200`:

```json
{ "success": true, "payload": null, "message": null, "code": null }
```

(v1 온보딩과 동일한 `BaseResponse<Unit>` 봉투. 지정된 닉네임·사진은 응답에 담지 않고 `GET /api/v1/members/me/profile` 로 확인한다.)

### 오류

| 상태 | code | 조건 |
|------|------|------|
| 400 | `COMMON-002` | 필수 필드 누락·타입 불일치 |
| 400 | `MEMBER-002` | 이미 온보딩을 완료한 회원 |
| 400 | `MEMBER-003` | 회원을 찾을 수 없음 |
| 400 | `MEMBER-005` | 지원하지 않는 기피 성분 코드 |
| 400 | `MEMBER-006` | 지원하지 않는 국가 코드 |
| 400 | `MEMBER-009` | 잘못된 맵기 선호 |
| 401 | — | 토큰 부재·위조·만료 |

`MEMBER-004`(닉네임 비어 있음)·`MEMBER-008`(프로필 사진 경로 형식)은 v2 에서 **발생할 수 없다** — 두 값이 요청에서 오지 않고 서버 생성값·후보는 배포 전 테스트로 유효성이 보장되기 때문이다.

---

## 기존 — `POST /api/v1/members/me/onboarding` (변경 없음)

1.0.0 앱이 사용한다. **요청 형식·검증 규칙·성공 응답·오류 코드가 하나도 바뀌지 않는다.**

```json
{
  "nickname": "길동이",
  "avoidanceSubstanceCodes": ["EGG", "MILK"],
  "countryCode": "US",
  "profileImageUrl": "images/default/profile/profile-default-512.png",
  "spicinessPreference": "SKIP"
}
```

| 필드 | 필수 | 누락 시 |
|------|------|---------|
| `nickname` | **필수**(non-null) | 400 `COMMON-002` |
| `profileImageUrl` | **필수**(non-null) | 400 `COMMON-002` |
| `countryCode` | **필수** | 400 `COMMON-002` |
| `spicinessPreference` | **필수** | 400 `COMMON-002` |
| `avoidanceSubstanceCodes` | 선택 | 기본 `[]` |

전송된 닉네임·사진은 **그대로 저장**되며 랜덤 값으로 덮어써지지 않는다(FR-007).

---

## 두 경로의 사후 동작 동등성 (FR-009)

온보딩 경로와 무관하게 다음이 동일하다:

- `GET /api/v1/members/me/profile` 응답 형식·필드 — 프로필 사진은 두 경우 모두 공개 베이스 URL 이 붙은 절대 URL 로 내려간다.
- `PATCH /api/v1|v2/members/me/profile` 로 닉네임·사진 변경 가능.
- 커뮤니티(리뷰·게시글·댓글) 작성자 표기.
- 온보딩 재시도 시 400 `MEMBER-002`.

## 계약 검증 방법

| 검증 대상 | 테스트 |
|-----------|--------|
| v2 온보딩 성공 + 닉네임·사진 자동 지정 | `MemberV2ControllerTest` (MockMvc + Testcontainers) |
| v2 에서 nickname/profileImageUrl 무시 | `MemberV2ControllerTest` |
| v2 필수 필드 누락 → `COMMON-002` | `MemberV2ControllerTest` |
| v2 재온보딩 → `MEMBER-002` / 미인증 → 401 | `MemberV2ControllerTest` |
| **v1 계약 완전 불변** | `MemberControllerTest` **무수정** 전량 통과 |
| 닉네임 생성 형식·중복, 이미지 후보 유효성·분포 | `OnboardingProfileDefaultsTest` (단위) |

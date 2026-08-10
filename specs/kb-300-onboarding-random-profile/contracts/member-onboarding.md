# Contract: 온보딩 API (X-API-Version 헤더 분기)

> 2026-08-10 개정 4: 계약 버전 표기를 **캘린더 버저닝 `yyyy.mm.sprint차수`**(토스페이먼츠 날짜 버저닝 커스텀)로 확정한다(사용자 결정). 이번 온보딩 계약 버전은 **`2026.08.07`** — 이상이면 닉네임·프로필 사진을 서버가 랜덤 지정하고, 그 외(미전송·이전 버전·형식 오류)는 종전 계약(두 필드 필수) 그대로다. 하위 호환 변경은 새 버전을 릴리즈하지 않고, 비호환 변경만 새 버전을 딴다.

## `POST /api/v1/members/me/onboarding`

인증: `Authorization: Bearer {accessToken}` (필수)

헤더: `X-API-Version: 2026.08.07` (선택) — 클라이언트가 기대하는 **계약 버전**(`yyyy.mm.sprint차수`, 연도→월→스프린트 순 숫자 비교, zero-pad 무관). **`2026.08.07` 이상이면** 서버 자동 지정으로 분기한다. 파싱 불가(오타·비정상 값)·미전송은 종전 계약으로 폴백한다. 1.0.0 앱은 이 헤더를 보내지 않는다. 앱 빌드 버전과 무관한 값이므로 iOS/Android 버전 번호가 갈라져도 임계값이 흔들리지 않는다.

### Request — `X-API-Version >= 2026.08.07` (신버전 앱)

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

닉네임은 서버가 영숫자 6자 코드(예: `K7M2XB`)로 생성하고, 프로필 사진은 아바타 6종 후보에서 무작위로 고른다. 요청에 `nickname`·`profileImageUrl` 을 담아 보내도 **무시**된다(오류 아님). 지정된 값은 프로필 수정 API 로 언제든 변경할 수 있다.

### Request — 헤더 미전송·`2026.08.07` 이전·형식 오류 (1.0.0 앱, 종전 계약 불변)

```json
{
  "nickname": "길동이",
  "avoidanceSubstanceCodes": ["EGG", "MILK"],
  "countryCode": "US",
  "profileImageUrl": "images/default/profile/profile-default-512.png",
  "spicinessPreference": "SKIP"
}
```

`nickname`·`profileImageUrl` 은 **필수** — 미전송·null 이면 400 `COMMON-002`. 전송된 값은 그대로 저장되며 랜덤 값으로 덮어써지지 않는다(FR-007). 구버전 앱의 필드 누락 버그가 조용히 랜덤 지정으로 흘러가지 않고 종전처럼 400 으로 드러난다.

### Response

성공 `200` (두 경로 동일):

```json
{ "success": true, "payload": null, "message": null, "code": null }
```

(지정된 닉네임·사진은 응답에 담지 않고 `GET /api/v1/members/me/profile` 로 확인한다.)

### 오류

| 상태 | code | 조건 |
|------|------|------|
| 400 | `COMMON-002` | 필수 필드 누락·타입 불일치 — 헤더 없으면 닉네임·사진도 필수 |
| 400 | `MEMBER-002` | 이미 온보딩을 완료한 회원 |
| 400 | `MEMBER-003` | 회원을 찾을 수 없음 |
| 400 | `MEMBER-004` | (헤더 없음) 보낸 닉네임이 비어 있음 |
| 400 | `MEMBER-005` | 지원하지 않는 기피 성분 코드 |
| 400 | `MEMBER-006` | 지원하지 않는 국가 코드 |
| 400 | `MEMBER-008` | (헤더 없음) 보낸 사진 경로 형식 위반(빈 문자열·절대 URL·512자 초과) |
| 400 | `MEMBER-009` | 잘못된 맵기 선호 |
| 401 | — | 토큰 부재·위조·만료 |

서버 생성 닉네임·후보 이미지 경로는 배포 전 단위 테스트(`OnboardingProfileDefaultsTest`)가 유효성을 보장하므로 헤더 경로에서 `MEMBER-004`/`MEMBER-008` 은 발생하지 않는다.

## 사후 동작 동등성 (FR-009)

온보딩 경로(헤더 유무)와 무관하게 다음이 동일하다:

- `GET /api/v1/members/me/profile` 응답 형식·필드 — 프로필 사진은 두 경우 모두 공개 베이스 URL 이 붙은 절대 URL 로 내려간다.
- `PATCH /api/v1|v2/members/me/profile` 로 닉네임·사진 변경 가능.
- 커뮤니티(리뷰·게시글·댓글) 작성자 표기.
- 온보딩 재시도 시 400 `MEMBER-002`.

## 계약 검증 방법

| 검증 대상 | 테스트 |
|-----------|--------|
| `2026.08.07`·상위 버전 헤더 + 닉네임·사진 없이 → 200 + 자동 지정 | `MemberControllerTest` (MockMvc + Testcontainers) |
| `2026.08.07` + 닉네임·사진 전송 → 무시되고 서버 지정값 저장 | `MemberControllerTest` |
| `2026.08.07` 이라도 필수 필드(국가) 누락 → `COMMON-002` | `MemberControllerTest` |
| 이전 버전(`2026.08.06`)·형식 오류 헤더 → 종전 계약(생략 시 400) | `MemberControllerTest` |
| 헤더 없음 + 닉네임/사진 생략·null → 400 `COMMON-002` (종전 계약) | `MemberControllerTest` |
| 헤더 없음 + 전송값 그대로 저장 | `MemberControllerTest` 기존 시나리오 |
| 계약 버전 파싱·비교(3파트 강제·오타 null·숫자 비교) | `ApiVersionTest` (단위) |
| 닉네임 생성 형식·중복, 이미지 후보 유효성·분포 | `OnboardingProfileDefaultsTest` (단위) |

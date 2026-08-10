# Contract: 온보딩 API (v1 완화)

> 2026-08-10 개정: 별도 v2 엔드포인트를 열지 않고 **기존 v1 온보딩의 `nickname`·`profileImageUrl` 을 선택 필드로 완화**한다(사용자 결정). 미전송·null 이면 서버가 랜덤 지정한다.

## `POST /api/v1/members/me/onboarding`

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
| `nickname` | `string` | 선택 | 미전송·null 이면 서버가 영숫자 6자 코드(예: `K7M2XB`)로 생성. 전송하면 그대로 저장(FR-007) |
| `avoidanceSubstanceCodes` | `string[]` | 선택 (기본 `[]`) | 기피 성분 코드. 카탈로그에 없는 코드는 400 `MEMBER-005` |
| `countryCode` | `string` | **필수** | ISO 2자리. 미지원 코드는 400 `MEMBER-006` |
| `profileImageUrl` | `string` | 선택 | 미전송·null 이면 서버가 아바타 6종 후보에서 무작위 지정. 전송하면 그대로 저장 |
| `spicinessPreference` | `string` | **필수** | `SKIP`·`NONE`·`MILD`·`MEDIUM`·`HOT`·`EXTREME`. 그 외는 400 `MEMBER-009` |

**하위 호환**: 1.0.0 앱은 다섯 필드를 항상 전부 보내므로 동작이 하나도 달라지지 않는다 — 필수→선택 완화는 기존 유효 요청을 전부 그대로 받는다. 달라지는 것은 "닉네임·사진 미전송이 400 이 아니라 자동 지정"이라는 점뿐이며, 이 요청은 신버전 앱만 보낸다.

`countryCode`·`spicinessPreference` 누락은 Jackson 역직렬화 실패 → 400 `COMMON-002`(기존 `GlobalExceptionHandler` 경로, 종전과 동일).

### Response

성공 `200`:

```json
{ "success": true, "payload": null, "message": null, "code": null }
```

(종전과 동일한 `BaseResponse<Unit>` 봉투. 지정된 닉네임·사진은 응답에 담지 않고 `GET /api/v1/members/me/profile` 로 확인한다.)

### 오류

| 상태 | code | 조건 |
|------|------|------|
| 400 | `COMMON-002` | 필수 필드(국가·맵기) 누락·타입 불일치 |
| 400 | `MEMBER-002` | 이미 온보딩을 완료한 회원 |
| 400 | `MEMBER-003` | 회원을 찾을 수 없음 |
| 400 | `MEMBER-004` | 닉네임을 **보냈는데** 비어 있음(공백) |
| 400 | `MEMBER-005` | 지원하지 않는 기피 성분 코드 |
| 400 | `MEMBER-006` | 지원하지 않는 국가 코드 |
| 400 | `MEMBER-008` | 사진 경로를 **보냈는데** 형식 위반(빈 문자열·절대 URL·512자 초과) |
| 400 | `MEMBER-009` | 잘못된 맵기 선호 |
| 401 | — | 토큰 부재·위조·만료 |

서버 생성 닉네임·후보 이미지 경로는 배포 전 단위 테스트(`OnboardingProfileDefaultsTest`)가 유효성을 보장하므로 자동 지정 경로에서 `MEMBER-004`/`MEMBER-008` 은 발생하지 않는다.

## 사후 동작 동등성 (FR-009)

닉네임·사진을 보냈든 자동 지정받았든 다음이 동일하다:

- `GET /api/v1/members/me/profile` 응답 형식·필드 — 프로필 사진은 두 경우 모두 공개 베이스 URL 이 붙은 절대 URL 로 내려간다.
- `PATCH /api/v1|v2/members/me/profile` 로 닉네임·사진 변경 가능.
- 커뮤니티(리뷰·게시글·댓글) 작성자 표기.
- 온보딩 재시도 시 400 `MEMBER-002`.

## 계약 검증 방법

| 검증 대상 | 테스트 |
|-----------|--------|
| 닉네임·사진 생략/null → 200 + 자동 지정 | `MemberControllerTest` (MockMvc + Testcontainers) |
| 닉네임·사진 전송 시 그대로 저장(랜덤으로 덮이지 않음) | `MemberControllerTest` 기존 시나리오 |
| 필수 필드(국가·맵기) 누락 → `COMMON-002` | `MemberControllerTest` |
| 재온보딩 → `MEMBER-002` / 미인증 → 401 | `MemberControllerTest` |
| 닉네임 생성 형식·중복, 이미지 후보 유효성·분포 | `OnboardingProfileDefaultsTest` (단위) |

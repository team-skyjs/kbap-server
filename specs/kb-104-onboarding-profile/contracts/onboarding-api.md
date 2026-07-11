# API Contract: 온보딩 제출·내 프로필 조회

**Date**: 2026-07-12 | **Plan**: [../plan.md](../plan.md)

공통: 모든 응답은 `BaseResponse` 봉투(`success`·`payload`·`message`). 인증은 `Authorization: Bearer <access-token>` — 부재·위조 401 `유효하지 않은 인증 토큰입니다`, 만료 401 `만료된 인증 토큰입니다`.

## POST /api/v1/members/me/onboarding — 온보딩 제출

### Request

```json
{
  "nickname": "길동이",
  "avoidanceSubstanceCodes": ["EGG", "MILK"],
  "countryCode": "US",
  "appLanguage": "en"
}
```

| 필드 | 타입 | 필수 | 제약 |
|------|------|------|------|
| nickname | string | ✔ | trim 후 1자 이상 (저장 상한: 30자 칼럼) |
| avoidanceSubstanceCodes | string[] | ✔ (빈 배열 허용) | 회피 성분 카탈로그 81종 enum name. 중복은 제거되어 저장 |
| countryCode | string | ✔ | 지정 국가 ENUM name (예: `KR`, `US`) |
| appLanguage | string | ✔ | 지원 10개국어 code 정확 일치: `ko`·`zh-Hans`·`en`·`ja`·`zh-Hant`·`vi`·`id`·`th`·`ru`·`es` |

### Responses

- **200** 성공 — 프로필 저장 + 온보딩 COMPLETED 전이:

```json
{ "success": true, "payload": null, "message": null }
```

(payload 는 `Unit` — 클라이언트는 완료 후 홈 진입. 프로필 값이 필요하면 GET /members/me 사용.)

- **400** 검증 실패(저장·전이 없음) — `message` 예:
  - `닉네임은 비어 있을 수 없습니다`
  - `지원하지 않는 기피 성분 코드입니다`
  - `지원하지 않는 국가 코드입니다`
  - `지원하지 않는 언어입니다`
  - `이미 온보딩을 완료했습니다` (재제출)
- **401** 미인증 (헤더 부재·위조·만료)
- **404** `해당 회원을 찾을 수 없습니다` (탈퇴 등으로 회원 부재)

## GET /api/v1/members/me — 내 프로필·온보딩 상태 조회

### Request

본문 없음. `Authorization: Bearer <access-token>` 만 요구.

### Responses

- **200** 성공:

```json
{
  "success": true,
  "payload": {
    "memberId": 1,
    "nickname": "길동이",
    "avoidanceSubstanceCodes": ["EGG", "MILK"],
    "countryCode": "US",
    "appLanguage": "en",
    "onboardingCompleted": true
  },
  "message": null
}
```

온보딩 미완료 회원은 `nickname`·`countryCode`·`appLanguage` 가 `null`, `avoidanceSubstanceCodes` 빈 배열, `onboardingCompleted: false` — 클라이언트는 이 플래그로 온보딩 화면 분기.

- **401** 미인증
- **404** 회원 부재

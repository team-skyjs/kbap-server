# Contract: PATCH /api/v2/members/me/profile

내 프로필 부분 수정 — **국적(countryCode) 변경 불가 버전.** v1(`PATCH /api/v1/members/me/profile`)과 공존하며, v1 계약은 변경하지 않는다.

## Request

- **Method/Path**: `PATCH /api/v2/members/me/profile`
- **Auth**: `Authorization: Bearer <access token>` 필수 (JwtAuthenticationFilter 보호 경로)
- **Body** (모든 필드 optional — null/누락 = 변경 없음):

```json
{
  "nickname": "string | null",
  "avoidanceSubstanceCodes": ["string"],
  "profileImageUrl": "string | null",
  "spicinessPreference": "string | null"
}
```

- `countryCode` 필드는 **존재하지 않는다.** 요청 JSON 에 포함해 보내면 무지 필드로 **무시**된다(400 아님).

## Response

- **200**: `{ "success": true, "payload": null }` (`BaseResponse<Unit>` — v1 과 동일 봉투)
- **401**: 토큰 없음/무효 — 기존 인증 실패 규약(AUTH-*) 그대로
- **404 계열**: 활성 회원 아님 — 기존 `getMember` 예외 규약 그대로

## v1 과의 차이 (요약)

| | v1 | v2 |
|---|----|----|
| `countryCode` 요청 필드 | 있음 — 값 주면 국적 변경 | **없음 — 어떤 요청으로도 국적 불변** |
| 나머지 필드·의미 | 부분 수정(null=변경 없음) | 동일 |
| 응답 | `BaseResponse<Unit>` | 동일 |

# Contract 변경: GET /api/v1/members/me/profile

기존 엔드포인트 응답에 필드 1개 추가(하위 호환 — 기존 필드 불변).

## 추가 필드

| 필드 | 타입 | nullable | 값 | 설명 |
|------|------|----------|-----|------|
| `payload.provider` | string | no | `GOOGLE` \| `APPLE` | 가입 시 연동한 소셜 제공자. DB 저장값 그대로. |

## 응답 예시 (변경 후)

```json
{
  "success": true,
  "payload": {
    "memberId": 1,
    "provider": "GOOGLE",
    "nickname": "길동",
    "avoidanceSubstanceCodes": ["PORK"],
    "countryCode": "US",
    "appLanguage": "en",
    "profileImageUrl": "https://cdn.example.com/profile-image/2026/07/18/1/abc.jpg",
    "spicinessPreference": 5,
    "onboardingCompleted": true,
    "ranking": { "tier": "BRONZE", "level": 1, "score": 0, "nextTier": "SILVER", "pointsToNext": 100 }
  }
}
```

- 에러 계약 변경 없음(기존 401/MEMBER 에러 코드 그대로).
- `PATCH /api/v1/members/me/profile`(수정 API) 요청 계약 변경 없음 — provider 는 조회 전용.

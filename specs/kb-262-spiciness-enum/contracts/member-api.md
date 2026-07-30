# API Contract 변경분: 맵기 선호 ENUM 전환

공통: `spicinessPreference` 는 모든 지점에서 정수 → **enum 문자열**(`SKIP`|`NONE`|`MILD`|`MEDIUM`|`HOT`|`EXTREME`)로 바뀐다. 하위 호환 없음 — 정수 전송은 400.

## POST /api/v1/members/onboarding (온보딩)

| 항목 | 전 | 후 |
|------|-----|-----|
| `spicinessPreference` (필수) | `-1` 또는 `0~10` 정수 | 6단계 문자열. 건너뛰기 = `"SKIP"` 명시 전송 |
| 미전송 | 400 COMMON-002 | 동일(400 COMMON-002) |
| 유효 범위 밖 | 400 MEMBER-009 | 6단계 외 문자열·정수 → 400 MEMBER-009 |

요청 예:

```json
{
  "nickname": "김맵찔",
  "avoidanceSubstanceCodes": ["SHRIMP"],
  "countryCode": "US",
  "profileImageUrl": "images/default/profile/profile-default-512.png",
  "spicinessPreference": "HOT"
}
```

## PATCH /api/v1/members/me (프로필 수정)

- `spicinessPreference` 선택 필드 — 생략 시 기존 값 유지(부분 수정 규약 유지), 값이 있으면 6단계 문자열만 유효(그 외 400 MEMBER-009).

## GET /api/v1/members/me (내 프로필)

- 응답 `payload.spicinessPreference`: 정수 → 6단계 문자열. 미설정 회원은 `"SKIP"`.

## 관리자 회원 목록 (AdminMemberDetailView)

- `spicinessPreference`: 정수 → 6단계 문자열.

## Swagger (MemberApi)

- 온보딩·수정 operation 설명의 "-1(미설정) 또는 0~10 정수" 문구와 요청 ExampleObject 4건의 정수 값을 6단계 문자열 기준으로 갱신(FR-004).

# API Contract Delta: 프로필 사진 URL·맵기 선호 (KB-147)

신규 엔드포인트 없음 — 기존 3개 API 의 요청/응답에 `profileImageUrl`·`spicinessPreference` 필드 추가. 모든 응답은 `BaseResponse<T>` 봉투 규약 그대로.

## 1. POST /api/v1/members/me/onboarding — 온보딩 (요청 필드 추가)

```jsonc
// Request (추가 필드만 — 나머지 기존과 동일)
{
  "nickname": "홍길동",
  "avoidanceSubstanceCodes": ["PORK"],
  "countryCode": "US",
  "appLanguage": "en",
  "profileImageUrl": "https://cdn.example.com/profiles/abc.jpg",  // 선택 — 생략·빈 문자열 = 미설정
  "spicinessPreference": 7                                        // 선택 — 0~10, 생략 = 기본 5 유지
}
```

- `profileImageUrl` 생략/빈 문자열 → 온보딩 정상 완료, 사진 미설정(null).
- 형식 불합격(https 아님·URI 파싱 실패·호스트 없음·512자 초과) 또는 허용 호스트 밖 → **400 `MEMBER-008`** (`success=false`), 저장 안 됨.
- `spicinessPreference` 생략 → 기본값 5 유지. 0~10 밖 → **400 `MEMBER-009`**, 저장 안 됨.

## 2. GET /api/v1/members/me/profile — 내 프로필 조회 (응답 필드 추가)

```jsonc
// Response payload (추가 필드만)
{
  "memberId": 1,
  "nickname": "홍길동",
  "avoidanceSubstanceCodes": ["PORK"],
  "countryCode": "US",
  "appLanguage": "en",
  "onboardingCompleted": true,
  "profileImageUrl": "https://cdn.example.com/profiles/abc.jpg",  // 미설정이면 null
  "spicinessPreference": 7,                                       // 항상 존재 (기본 5)
  "ranking": { /* 기존과 동일 */ }
}
```

## 3. PATCH /api/v1/members/me/profile — 프로필 부분 수정 (요청 필드 추가)

| 전송 값 | 의미 | 결과 |
|---------|------|------|
| 필드 미전송 | 유지 | 기존 사진 그대로 (기존 부분 수정 규칙) |
| `"https://cdn.../new.jpg"` | 교체 | 검증 통과 시 새 URL 저장 |
| `""` (빈 문자열/공백) | **제거** | 사진 미설정(null)으로 복귀 |
| 불합격 URL | 거절 | 400 `MEMBER-008`, 아무 필드도 변경 안 됨 |

`spicinessPreference` (맵기 — non-null 속성이라 제거 없음):

| 전송 값 | 의미 | 결과 |
|---------|------|------|
| 필드 미전송 | 유지 | 기존 맵기 그대로 |
| `0`~`10` | 교체 | 새 값 저장 |
| 범위 밖 (`11`, `-1` 등) | 거절 | 400 `MEMBER-009`, 아무 필드도 변경 안 됨 |

## 에러 계약

| 코드 | HTTP | 조건 |
|------|------|------|
| `MEMBER-008` | 400 | https 아님 · URI 파싱 실패 · 호스트 없음 · 512자 초과 · 허용 호스트 목록(설정된 환경) 밖 |
| `MEMBER-009` | 400 | 맵기 선호가 0~10 범위 밖 |

기존 에러(`MEMBER-003` 미존재 회원, `AUTH-*` 인증)는 무변경. 하위 호환: 새 필드를 모르는 기존 클라이언트는 필드를 안 보내면(온보딩·수정) 기존과 100% 동일하게 동작하고, 조회 응답의 추가 필드는 무시하면 된다.

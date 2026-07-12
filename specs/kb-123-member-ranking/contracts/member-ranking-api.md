# API 계약: 회원 랭킹

모든 응답은 공통 봉투 `BaseResponse<T>`(`{success, payload, message}`) 로 감싼다. 모든 경로는 `/api/v1` 로 시작한다. 두 엔드포인트 모두 `Authorization: Bearer {accessToken}` 이 필요하다(`/api/v1/members/*` 는 JWT 필터가 이미 덮는다).

---

## 1. 랭킹 상세 조회 (신규)

`GET /api/v1/members/me/ranking`

### 200 OK

```jsonc
{
  "success": true,
  "payload": {
    "tier": "explorer",        // 안정 키 — FE 가 i18n 으로 번역한다(서버는 번역명을 주지 않는다)
    "level": 3,                // 1~7
    "score": 128,              // 누적 점수
    "nextTier": "regular",     // 최고 등급이면 null
    "pointsToNext": 52,        // 다음 등급 진입 점수 − score. 최고 등급이면 null
    "breakdown": {
      "reviews":   { "count": 8, "points": 80 },
      "diversity": { "count": 6, "points": 30 },   // count = 리뷰한 고유 음식 수
      "scans":     { "count": 9, "points": 18 }
    }
  },
  "message": null
}
```

- `breakdown` 세 항목의 `points` 합은 항상 `score` 와 같다.
- **현재 제약**: 리뷰 도메인이 없어 `reviews.count`·`diversity.count` 는 항상 0, `points` 도 0이다. 리뷰 기능 도입 시 채워진다.
- 가입 직후 회원은 모든 카운트가 0이라 `score` 0 · `tier` `newcomer` · `pointsToNext` 30 이다.

### 401 Unauthorized

토큰 부재·위조·만료. payload 없이 실패 봉투를 반환한다.

### 400 Bad Request

회원을 찾을 수 없음(탈퇴 회원의 유효 토큰 등) — 기존 회원 API 와 동일하게 `MEMBER_NOT_FOUND` 를 400 으로 반환한다.

---

## 2. 프로필 조회 (기존 — 랭킹 요약 추가)

`GET /api/v1/members/me/profile`

### 200 OK

```jsonc
{
  "success": true,
  "payload": {
    "memberId": 1,
    "nickname": "Jane",
    "avoidanceSubstanceCodes": ["PORK", "PEANUT"],
    "countryCode": "US",
    "appLanguage": "en",
    "onboardingCompleted": true,

    "ranking": {               // 신규 — 랭킹 요약(breakdown 없음)
      "tier": "explorer",
      "level": 3,
      "score": 128,
      "nextTier": "regular",
      "pointsToNext": 52
    }
  },
  "message": null
}
```

- 기존 필드는 그대로 유지된다(추가만).
- `ranking` 의 다섯 값은 같은 회원의 랭킹 상세 응답과 **항상 일치**한다(같은 산정 로직).
- 프로필 탭은 이 응답 하나로 그려진다(추가 호출 없음).

---

## 등급 사다리 (계약값 — 변경 금지)

| level | tier | 진입 점수 |
|---|---|---|
| 1 | `newcomer` | 0 |
| 2 | `taster` | 30 |
| 3 | `explorer` | 80 |
| 4 | `regular` | 180 |
| 5 | `gourmet` | 350 |
| 6 | `kfood_master` | 600 |
| 7 | `korean_at_heart` | 1000 |

점수 공식: `score = 리뷰 수 × 10 + 고유 음식 수 × 5 + 스캔 수 × 2`. 점수가 진입 점수와 정확히 같으면 그 등급이다(30 → `taster`).

`breakdown.scans.count` 의 **스캔 수는 메뉴판 1장을 1회로 센다** — 한 번의 스캔에서 음식이 여러 개 매칭돼도 1회이며, 하나도 매칭되지 않아도 1회로 센다.

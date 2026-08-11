# Contract: 회원 통화 — 온보딩 · 프로필 수정 · 프로필 조회

**Owner**: `api/src/main/kotlin/com/kbap/api/member/`

**Asserted by**: `MemberControllerTest`(MockMvc + MySQL Testcontainers)

**버전**: 올리지 않는다. 요청은 **선택 필드 추가**, 응답은 **필드 추가** — 둘 다 하위 호환이다(팀 정책: 비호환 변경만 릴리즈).

---

## 1. `POST /api/v1/members/me/onboarding` — **요청 계약 변경 없음**

통화는 **요청 필드가 아니다.** 본문에 `currency` 를 넣어도 서버는 읽지 않는다 — DTO 에 필드 자체가 없으므로 무시가 구조적으로 보장된다(FR-003).

| 필드 | 변화 |
|------|------|
| `countryCode` | 그대로 필수 |
| 그 외 기존 필드 | 그대로 |
| `currency` | **없음** |

**부수 효과**: 온보딩이 성공하면 회원의 통화가 `countryCode` 에 대응하는 값으로 저장된다. 응답 본문에는 나타나지 않는다(온보딩 응답 형태 불변) — 확인은 프로필 조회로 한다.

`X-API-Version` 분기(`2026.08.07` 이상 = 닉네임·사진 서버 지정)는 통화와 **무관**하다. 두 경우 모두 통화가 지정된다.

---

## 2. `PATCH /api/v1/members/me` — 선택 필드 `currency` 추가

### 요청

| 필드 | 타입 | 필수 | 의미 |
|------|------|------|------|
| `currency` | String? | 아니오 | ISO 4217 3자 대문자. **미전송이면 기존 값 유지** |
| `countryCode` | String? | 아니오 | 기존. **바꿔도 통화는 변하지 않는다**(FR-007) |
| 그 외 | | | 기존 그대로 |

### 동작 표

| 요청 | 결과 |
|------|------|
| `currency` 만 전송 | 통화 교체, 국가 불변 |
| `countryCode` 만 전송 | 국가 교체, **통화 불변** |
| 둘 다 전송 | 둘 다 요청대로 저장(통화가 국가에 덮이지 않는다) |
| 둘 다 미전송 | 둘 다 유지 |
| 지원 목록 밖 `currency` | **400 `MEMBER-010`**, 기존 통화 유지 |

값은 **정확 일치**로만 받는다 — `krw`·`" KRW "` 같은 변형을 정규화하지 않고 거절한다(`lang` 취급과 동일한 태도).

---

## 3. `GET /api/v1/members/me` — 응답에 `currency` 추가

```json
{
  "success": true,
  "payload": {
    "memberId": 7,
    "provider": "GOOGLE",
    "nickname": "매운거좋아",
    "avoidanceSubstanceCodes": ["PORK"],
    "countryCode": "JP",
    "currency": "KRW",
    "profileImageUrl": "https://cdn.example.com/profile/7.webp",
    "spicinessPreference": "HOT",
    "onboardingCompleted": true,
    "ranking": { "tier": "BRONZE", "level": 2, "score": 40, "nextTier": "SILVER", "pointsToNext": 60 }
  },
  "message": null,
  "code": null
}
```

- `currency` — 현재 통화. **온보딩 전 회원은 `null`** 이고 조회는 정상이다(FR-011)
- 위 예시는 FR-007 이 만드는 **정상적인 어긋남**을 보여준다 — 국가는 `JP` 인데 통화는 `KRW`(일본 이사 후 국가만 바꾼 회원)

---

## 회귀 판정

`./gradlew :api:test --tests "com.kbap.api.member.*"` 가 **기존 시나리오를 수정 없이** 통과해야 한다. 이 파일들에 허용되는 편집은 **테스트 추가뿐**이다 — 기존 기대값을 고쳐야 통과한다면 계약이 깨진 것이다.

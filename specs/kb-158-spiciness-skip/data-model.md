# Data Model: 맵기 선호 미설정(스킵) 허용 — -1 센티널

DB 스키마·Flyway·엔티티 구조 무변경. 변경은 값 객체의 허용 집합과 기본값뿐.

## MemberProfile (값 객체, `:domain:member` — 변경)

| 필드 | 타입 | 변경 전 | 변경 후 |
|------|------|---------|---------|
| `spicinessPreference` | `Int` | 허용 `0..10`, 기본 `DEFAULT_SPICINESS_PREFERENCE = 5` | 허용 `{-1} ∪ 0..10`, 기본 `SPICINESS_UNSET = -1` |

- 검증(단일 규칙, 두 지점 동일): `spicinessPreference == SPICINESS_UNSET || spicinessPreference in SPICINESS_RANGE`
  - `init` require — 생성 경로 전체 방어
  - `validatedSpiciness` — `updatedWith` 경로, 위반 시 `BusinessException(MEMBER-009)`
- `SPICINESS_RANGE = 0..10` 의미 유지("설정된 값"의 범위), `DEFAULT_SPICINESS_PREFERENCE` 상수 삭제.
- `empty()` → `spicinessPreference = SPICINESS_UNSET`

### 상태 전이

```text
미설정(-1) ──[온보딩/수정에서 0..10 전송]──▶ 설정(n)
설정(n)   ──[수정에서 -1 명시 전송]────────▶ 미설정(-1)
설정(n)   ──[수정에서 미전송]──────────────▶ 설정(n) 유지   (부분 수정 규약 불변)
미설정(-1)──[온보딩에서 미전송 또는 -1]────▶ 미설정(-1)
```

## MemberProfileJson (JSON 직렬화 모델, member 모듈 내부 — 상수 참조만 변경)

| 필드 | 변경 전 기본값 | 변경 후 기본값 |
|------|----------------|----------------|
| `spicinessPreference` | `MemberProfile.DEFAULT_SPICINESS_PREFERENCE` (5) | `MemberProfile.SPICINESS_UNSET` (-1) |

- 저장 위치: `member.profile` JSON 컬럼 — 스키마 무변경, -1 이라는 정수가 들어갈 뿐.
- **레거시 해석**: 키 부재 행은 실존하지 않음(consolidation 마이그레이션이 전 행 백필 — research.md D4). 기본값 -1 은 향후 키 없는 JSON 유입에 대한 방어이며 기존 회원 표시값 변화 0건.

## ErrorCode (`:core` — 메시지만 변경)

| 코드 | 상태 | 변경 전 메시지 | 변경 후 메시지 |
|------|------|----------------|----------------|
| `MEMBER-009` (`INVALID_SPICINESS_PREFERENCE`) | 400 | 맵기 선호는 0~10 사이여야 합니다 | 맵기 선호는 -1(미설정) 또는 0~10 사이여야 합니다 |

## 무변경 확인

- `Member` 엔티티·`MemberService`·도메인 dto(`MemberProfileInput`·`ProfileUpdateInput`·`MyProfileResult`), API DTO(`OnboardingRequest`·`ProfileUpdateRequest`·`MyProfileResponse`) — 시그니처·타입 전부 그대로(-1 이 기존 `Int?`/`Int` 통로로 흐름).
- `food.spiciness` (음식 매운맛) — 별개 컬럼, 범위 밖.

# Data Model: 사용자 프로필 맵기 설정 ENUM 전환 (Phase 1)

## SpicinessPreference (신규 enum — `common.domain.member.model`)

| 값 | 의미 | 구 정수 대응(이관) |
|----|------|--------------------|
| `SKIP` | 맵기 화면 건너뜀 — 미설정 | -1, 속성 결손 |
| `NONE` | 매운맛 못 먹음 | 0 |
| `MILD` | 순한맛 | 1~3 |
| `MEDIUM` | 보통 | 4~6 |
| `HOT` | 매운맛 | 7~8 |
| `EXTREME` | 극한맛 | 9~10 |

- 조회용 lookup: 미지 문자열 → `BusinessException(MEMBER-009)`. 위치는 enum companion(`from(raw: String)`).
- 이관 매핑 규칙은 Flyway SQL 에만 존재(일회성) — 런타임 코드에 정수 매핑을 두지 않는다.

## MemberProfile (변경 — 값 객체)

| 필드 | 전 | 후 |
|------|-----|-----|
| `spicinessPreference` | `Int` (-1 또는 0~10, init require) | `SpicinessPreference` (non-null — 타입이 검증을 대체, init require 삭제) |

- `SPICINESS_UNSET` 상수 삭제 → `SpicinessPreference.SKIP` 이 대체.
- `empty()` → `SKIP`.
- `updatedWith(spicinessPreference: String?)` — raw 문자열을 받아 `validatedSpiciness` 가 `SpicinessPreference.from` 으로 변환(MEMBER-009). null 은 기존 값 유지(부분 수정 규약 유지).
- `common.domain.Spiciness`(RANGE 0..10) import 제거 — food 전용으로 남는다.

## MemberProfileJson (변경 — 영속 JSON 표현)

| 필드 | 전 | 후 |
|------|-----|-----|
| `spicinessPreference` | `Int = SPICINESS_UNSET` | `SpicinessPreference = SpicinessPreference.SKIP` |

- DB `member.profile` JSON 에는 enum 이름 문자열(`"HOT"`)로 저장된다.
- 결손 필드 → 기본값 `SKIP`. 이관 후 남은 비정상 값 → 역직렬화 실패(조용한 유실 금지).

## 도메인 dto (변경)

| 타입 | 필드 | 전 → 후 |
|------|------|---------|
| `MemberProfileInput` | `spicinessPreference` | `Int` → `String` (raw — 검증은 MemberProfile) |
| `ProfileUpdateInput` | `spicinessPreference` | `Int?` → `String?` |
| `MyProfileResult` | `spicinessPreference` | `Int` → `String` (`enum.name`) |

## ErrorCode (메시지만 변경)

- `INVALID_SPICINESS_PREFERENCE("MEMBER-009", 400, ...)` — 코드·상태 유지, 메시지를 "맵기 선호는 SKIP·NONE·MILD·MEDIUM·HOT·EXTREME 중 하나여야 합니다" 로 갱신(코드 분기 규약상 메시지는 자유 변경).

## DB 이관 (Flyway — `V<생성시각>__member_spiciness_enum.sql`)

상태 전이: `profile->'$.spicinessPreference'` 정수(-1~10)·결손 → enum 이름 문자열 6종 중 하나.

1. 결손 행: `JSON_SET(profile, '$.spicinessPreference', 'SKIP')`
2. 정수 행: `CASE` 매핑(data table 의 대응 열) 후 `JSON_SET`
3. 가드: 6종 외 값 잔존 행 존재 시 NOT NULL 위반으로 마이그레이션 실패(잔존 0행이면 no-op)

스키마 DDL 변경 없음(컬럼은 JSON 그대로).

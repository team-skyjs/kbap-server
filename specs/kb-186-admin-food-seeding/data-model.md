# Data Model: 신규 음식 적재 관리자 API

스키마 변경 없음(kb-186 범위). `food` 테이블·`uq_food_korean_name` 그대로 사용. 센티널 관련 스키마(`avoidance_substances` nullable)는 kb-182 소유.

## 변경 1 — `MemberRole` (:domain:member)

```kotlin
enum class MemberRole {
    USER,
    ADMIN,
}
```

- 파급: `JwtTokenParser.parseAccessToken` 은 `MemberRole.entries` 매칭이라 무수정 동작. `AuthApplicationService` 는 `MemberRole.USER` 명시 발급이라 영향 없음. member 테이블에 role 컬럼 없음(토큰 클레임 전용) — DB 변경 불필요.

## 변경 2 — `ErrorCode` (:core)

```kotlin
ADMIN_FORBIDDEN("AUTH-008", 403, "관리자만 사용할 수 있는 API 입니다"),
```

## 변경 3 — `SeedIncompleteResult` (:domain:food, 신규 DTO)

```kotlin
data class SeedIncompleteResult(
    val requested: Int,   // dedup·blank 필터 후 실제 판정 대상 수
    val created: Int,     // 신규 INCOMPLETE 로 적재된 수
    val skipped: Int,     // korean_name 기존 존재로 건너뛴 수 (requested = created + skipped)
)
```

## 변경 4 — `FoodService.seedIncomplete` (:domain:food)

```kotlin
@Transactional
fun seedIncomplete(koreanNames: Set<String>): SeedIncompleteResult
```

- 입력: 요청 경계에서 확정된 `Set<String>`(trim·blank 제거·dedup 완료 — 헌법 V).
- 로직: 기존 이름 SELECT diff → 신규만 `createIncomplete`(insert-or-ignore upsert) → 카운트 반환.
- 상태전이 없음 — 생성만. 생성 상태는 `FoodContentStatus.INCOMPLETE` 고정.

## 적재되는 Food 행의 목표 상태

| 컬럼 | 값 | 소유 |
|---|---|---|
| korean_name | 요청 이름(그대로, 정규화 없음) | kb-186 |
| content_status | `INCOMPLETE` | 기존 |
| description | `"설명 준비 중"` placeholder | 기존 |
| name/description_translations | `{}` | 기존 |
| spiciness | **-1 (미조사 센티널)** | **kb-182** (`SPICINESS_UNASSESSED`) |
| avoidance_substances | **NULL (미조사)** | **kb-182** (nullable 스키마 + `upsertIncomplete` `'[]'`→`NULL` — R5 갭 참조) |
| status | `ACTIVE` | 기존 |

## API 계층 모델 (:app:api)

- `AdminFoodSeedRequest(koreanNames: List<String>?)` — `@field:NotNull`, 항목 `@field:Size(max = 255)`. `toKoreanNames(): Set<String>` 이 trim·blank 제거·dedup.
- `AdminFoodSeedResponse(requested: Int, created: Int, skipped: Int)` — `SeedIncompleteResult` 1:1 매핑(도메인 DTO 직노출 금지 원칙).

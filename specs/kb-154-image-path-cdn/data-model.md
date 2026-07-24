# Data Model: KB-154

**DB 스키마 변경 0 — Flyway 0건.** 기존 저장 구조의 값 의미만 바뀐다.

## 저장 (변경 없음, 값 의미만)

| 저장소 | 필드/키 | 이전 값 | 이후 값 |
|---|---|---|---|
| `member.profile` JSON | `profileImageUrl` | 전체 https URL | 경로(objectKey), 예: `profile-image/2026/07/18/1/uuid.jpg` (레거시 절대 URL 잔존 허용) |
| `food.image_ref` (VARCHAR 500) | `imageRef` | 참조 문자열(조합 없이 노출) | 경로(키). 레거시 절대 URL 잔존 허용 |

## 값 객체 변경

### `MemberProfile` (:domain:member)

- `validatedImageUrl(raw, allowedImageHosts)` → **`validatedImagePath(raw)`**
  - trim → 빈 문자열이면 null(제거 센티널 — 3분법 유지)
  - 512자 초과 → `BusinessException(MEMBER-008)`
  - `http://`·`https://` 시작(대소문자 무시) → `BusinessException(MEMBER-008)`
- `updatedWith(...)`·`of(...)` 체인에서 `allowedImageHosts` 파라미터 제거
- `Member.completeOnboarding`·`updateProfile` 시그니처에서 `allowedImageHosts` 제거

### 신규: `ImageUrls` (:core, Spring-free)

```
fun resolve(base: String, ref: String?): String?
  ref == null            → null
  ref.startsWith(http(s)://, ignoreCase) → ref  (레거시 통과)
  base.isBlank()         → ref                  (미설정 환경)
  else                   → base.trimEnd('/') + "/" + ref.trimStart('/')
```

## 조립 지점 (읽기 경로)

| 서비스 | 메서드 | resolve 대상 |
|---|---|---|
| `MemberService` | `getMyProfile` | `MyProfileResult.profileImageUrl` |
| `FoodService` | `getDetail` | `GetFoodDetailResult.imageRef` |
| `FoodService` | `foodPage`(browse·search 공용) | `FoodSummaryView.imageRef` |
| `HomeApplicationService` | `loadHome`(popularFoods·recentScans) | `FoodSummaryView.imageRef` |

## 설정

| 키 | 용도 | 변경 |
|---|---|---|
| `kbap.storage.public-base-url` | CDN 베이스(환경별 `IMAGE_PUBLIC_BASE_URL`) | 없음 — 소비처 추가만 |
| `kbap.member.profile-image-allowed-hosts` | 프로필 사진 허용 호스트 | **폐기** (prod·staging yml 에서 제거) |

## 상태 전이

없음 — 순수 값 변환.

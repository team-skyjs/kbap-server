# Data Model: 관리자 음식 상세 모달 UX 개선

DB 스키마·엔티티 변경 없음. 변경은 `:api` admin 뷰 DTO 1개뿐이다.

## AdminFoodDetailView (수정 — api/src/main/kotlin/com/kbap/api/admin/AdminFoodService.kt)

| 필드 | 타입 | 변경 | 설명 |
|------|------|------|------|
| id | Long | 유지 | |
| koreanName | String | 유지 | |
| description | String | 유지 | |
| spiciness | Int | 유지 | |
| contentStatus | FoodContentStatus | 유지 | |
| imageRef | String? | 유지 | 키 원문 — 수정 입력 필드용 |
| **imageUrl** | **String?** | **추가** | `ImageUrls.resolve(imagePublicBaseUrl, imageRef)` 결과. `imageRef == null`이면 null → 템플릿이 플레이스홀더 렌더 |
| nameTranslationsJson 외 | String… | 유지 | |

- 생성 규칙: `AdminFoodDetailView.from(food, toJson)`에 `imagePublicBaseUrl` 인자를 추가하거나 서비스에서 resolve 후 전달 — 구현 시 기존 `from` 시그니처 스타일을 따른다.
- 검증 규칙: 없음(읽기 전용 파생 값).

## 상태 전이

없음 — 조회 표시 전용.

## 관련 기존 모델 (무변경)

- `Food.imageRef` (`common.domain.food.model`) — 소스 필드, 무변경.
- `AdminMemberDetailView.profileImageUrl` — 동일 패턴 선례(참조용).

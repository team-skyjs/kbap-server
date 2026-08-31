# API 계약 변경점 (KB-301)

## 사용자 API — 변경 없음

- `GET /api/v1/foods/detail`: 응답 필드는 이미 `ingredients` 명칭을 사용(`FoodDetailResponse`) — 외부 계약 불변. 노출 대상도 READY 만으로 불변(FR-004, SC-003).
- 스캔·홈·검색 응답: food 상태에 의존하지 않거나 READY 필터 유지 — 불변.

## 관리자 API — 내부 계약 변경 (클라이언트 = 관리자 화면뿐)

| 항목 | 변경 전 | 변경 후 |
|------|---------|---------|
| `contentStatus` 값 집합 (목록·상세·수정·필터) | 6값 | 4값 `FAILED·PENDING_IMAGE·PENDING_REVIEW·READY` |
| 검수 응답 `avoidanceSubstances` (`AdminFoodContentReviewResponse`) | `avoidanceSubstances: List<FoodAvoidanceItem>` | `ingredients: List<FoodIngredient>` |
| 대시보드 상태별 카운트 | `incomplete` 등 구 상태 필드 | `failed` 등 신 상태 필드 4종 |
| 검수 액션 | 통과(PENDING_REVIEW→REVIEWED)·반려(필드 초기화·INCOMPLETE 회귀) | 승인(PENDING_REVIEW→READY)·반려(PENDING_REVIEW→FAILED, 사유 기록)·재제출(FAILED→PENDING_IMAGE) |

- 관리자 화면(서버 렌더 템플릿)은 같은 레포에서 함께 갱신 — 외부 소비자 없음.
- 에러 계약: 허용되지 않는 전이는 기존 규약대로 `BusinessException`(BaseResponse.fail) — 신규 에러 코드 필요 시 FOOD- 채번.

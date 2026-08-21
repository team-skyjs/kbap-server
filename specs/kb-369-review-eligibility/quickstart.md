# Quickstart: KB-369 검증 시나리오

로컬(local 프로필) 기준. 회원 A 토큰 준비.

1. **자격 없음 거절**: A 가 스캔한 적 없는 READY 음식 F 에 `POST /api/reviews {foodId: F, rating: 4}` → 403 `REVIEW-004`.
2. **스캔 후 통과**: A 로 F 를 스캔(매칭 성공) → 같은 작성 요청 → 200, 리뷰 저장.
3. **수정 무검증**: 1~2 로 만든 리뷰를 `PATCH /api/reviews/{id}` → 200 (스캔 이력 삭제 후에도 성공).
4. **상세 3분기**: `GET /api/foods/F` — A 토큰: `reviewEligible: true` · 스캔 이력 없는 B 토큰: `false` · 무토큰: `false`.
5. **음식 오류 우선**: 존재하지 않는 foodId 로 작성 → 400 `FOOD-001`(REVIEW-004 아님).

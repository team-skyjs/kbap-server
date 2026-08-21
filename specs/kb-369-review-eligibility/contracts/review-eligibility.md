# Contract: 리뷰 작성 자격 + 상세 reviewEligible

## POST /api/reviews — 자격 검증 추가

기존 계약 유지 + 거절 케이스 1개 추가:

- 요청 회원의 활성 스캔 이력에 `foodId` 가 없으면 **403 `REVIEW-004`**:

```json
{ "success": false, "code": "REVIEW-004", "message": "스캔 이력이 있는 음식에만 리뷰를 작성할 수 있습니다" }
```

- 검증 순서: 음식 오류(400 FOOD-001)가 자격 오류보다 우선. 자격 통과 후 기존 검증(이미지 REVIEW-003 등) 진행.
- FE 분기: `REVIEW-004` 수신 시 "이 음식을 스캔하면 리뷰를 쓸 수 있어요" 안내.

## PATCH /api/reviews/{reviewId} — 무변경

자격 재검증 없음. 본인 리뷰 검증(REVIEW-001/002)만 기존대로.

## GET /api/foods/{foodId} — reviewEligible 추가

```json
{ "payload": { ..., "reviewEligible": true } }
```

| 조회자 | 값 |
|---|---|
| 회원 + 해당 음식 활성 스캔 이력 있음 | `true` |
| 회원 + 이력 없음(매칭 실패 스캔만 있는 경우 포함) | `false` |
| 비회원 | `false` (항상) |

- `true` ↔ POST 자격 통과는 같은 판정(같은 쿼리) — 상세 true 후 작성이 REVIEW-004 로 거절되는 불일치 없음.
- 용도: Write a review 버튼 게이트.

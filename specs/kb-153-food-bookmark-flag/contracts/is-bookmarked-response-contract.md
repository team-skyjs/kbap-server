# API Contract Delta: bookmarked 응답 필드 추가 (KB-153)

경로·메서드·파라미터·상태코드 무변경. 변경은 **응답 필드 `bookmarked`(boolean, 항상 존재) 추가**뿐이다 — 필드 추가는 하위 호환.

## 적용 대상

| API | 응답 타입 | bookmarked 위치 | 값 규칙 |
|-----|-----------|-------------------|---------|
| `GET /api/v1/foods` (리스트) | `Page<FoodSummaryResponse>` | 항목별 | 비회원 false / 회원: 항목별 실제 여부 |
| `GET /api/v1/foods/search` | `Page<FoodSummaryResponse>` | 항목별 | 동일 (구조 공유 — spec Assumption) |
| `GET /api/v1/foods/detail` | `FoodDetailResponse` | 최상위 | 비회원 false / 회원: 실제 여부 |
| `GET /api/v1/bookmarks` (목록) | `Page<FoodSummaryResponse>` | 항목별 | 항상 true (정의상 전부 북마크) |

## 값 규칙 상세

| 시나리오 | bookmarked |
|----------|--------------|
| 인증 헤더 없음 (비회원) | `false` — 누가 북마크했든 무관 |
| 회원, 해당 음식 북마크함 | `true` |
| 회원, 북마크 안 함 / 취소함(소프트삭제) | `false` |
| 회원 A 가 북마크, 회원 B 가 조회 | B 에게 `false` — 조회자 본인 기준 |

## 예시 (리스트, 회원 — 1번만 북마크)

```json
{
  "success": true,
  "payload": {
    "items": [
      { "foodId": 1, "name": "김치찌개", "bookmarked": true,  ... },
      { "foodId": 2, "name": "된장찌개", "bookmarked": false, ... }
    ],
    "hasNext": false,
    "nextCursor": null
  }
}
```

## 명시적 무변경

- 유효하지 않은/만료 토큰의 처리(401 등)는 기존 인증 정책 그대로 — 본 기능이 바꾸지 않는다.
- 페이지네이션·정렬·언어 처리·기존 필드 전부 무변경.

## Swagger 문구

- `FoodDetailResponse.bookmarked` `@Schema`: "조회 회원의 북마크 여부. 비회원 조회는 항상 false."
- `FoodApi`(리스트·검색·상세)·`BookmarkApi`(목록) 응답 설명에 동일 규칙 1줄 추가(북마크 목록은 "항상 true").

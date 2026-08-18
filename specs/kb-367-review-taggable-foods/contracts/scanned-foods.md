# API Contract: 스캔 음식 목록 조회 (리뷰 태그 후보)

## GET /api/foods/scanned

회원 전용 — 본인 스캔 이력에 매칭된 음식 요약 목록(중복 제거·마지막 스캔 시점 내림차순).

**요청**

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `lang` | O | 표시 언어 — 기존 규칙 동일(누락·빈값 400, 미지원 코드 en 폴백) |
| `cursor` | X | 직전 페이지 `nextCursor`(Long, 마지막 항목 foodId). 비정상 형식·재계산 불가 커서는 400 |
| `keyword` | X | 음식명 필터 — 기존 검색 규칙 동일(한국어명 또는 요청 언어 번역명 부분 일치, 대소문자 무시). 없으면 전체 |

헤더: `Authorization: Bearer <access>` 필수(미인증 401 — JWT 보호 경로 등록), `X-API-Version` 필수(기존 규약).

**응답** — 기존 음식 목록과 동일한 `Page<FoodSummaryResponse>`:

```jsonc
{
  "success": true,
  "payload": {
    "items": [
      { "foodId": 12, "name": "Kimchi Stew", "...": "FoodSummaryResponse 기존 필드 그대로(bookmarked·평점 요약 포함)" }
    ],
    "hasNext": true,
    "nextCursor": 12
  }
}
```

**규칙**

- 같은 음식 여러 번 스캔 → 1건, 마지막 스캔 시점 기준 정렬(재스캔하면 맨 앞으로).
- 삭제·비공개(READY 아님) 음식·음식 미매칭 스캔 항목 제외.
- 스캔 이력 없음·keyword 매칭 없음 → 빈 목록(`items: []`, `hasNext: false`, `nextCursor: null`).
- 페이지 크기 20 — 기존 음식 목록과 동일.

**무변경**: `GET /api/foods`·`GET /api/foods/search`·`POST /api/reviews` 동작·계약 불변. 리뷰 작성 시 스캔 검증 강제는 별도 태스크.

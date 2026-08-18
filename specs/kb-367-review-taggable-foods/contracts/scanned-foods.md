# API Contract: 스캔 음식 검색 (리뷰 태그 후보) — GET /api/foods/search?scope=scanned

별도 경로가 아니라 **기존 음식 검색 엔드포인트의 `scope` 파라미터**로 제공한다(2026-08-19 확정 — 초기 설계 `GET /api/foods/scanned` 를 통합으로 대체).

## GET /api/foods/search

**요청**

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `scope` | X | 검색 범위 — `all`(기본, 전체 음식)·`scanned`(본인 스캔 음식). 그 외 값 400(COMMON-002) |
| `keyword` | scope 따라 | `all` 이면 필수(빈/공백 400), `scanned` 면 옵션(미지정 시 스캔 목록 전체, 빈/공백은 400) |
| `lang` | O | 표시 언어 — 기존 규칙 동일(누락·빈값 400, 미지원 코드 en 폴백) |
| `cursor` | X | 직전 페이지 `nextCursor`(Long, 마지막 항목 foodId). **scope 간 커서 비호환.** 형식 오류·(scanned) 본인 스캔 이력에 없는 foodId 는 400(FOOD-002) |

**scope=scanned 규칙**

- **회원 전용** — 인증 없으면 401(AUTH-003). 보호는 URL 필터가 아니라 컨트롤러 분기가 소유한다(같은 경로의 scope=all 이 비회원 공개라 필터 등록 불가).
- 본인 스캔 이력에 매칭된 READY 음식만, 중복 제거·마지막 스캔 시점 내림차순(재스캔 시 맨 앞).
- 삭제·비공개 음식·음식 미매칭 스캔 항목 제외, 스캔 이력 없으면 빈 목록.
- keyword 매칭 규칙은 scope=all 과 동일(한국어명 또는 요청 언어 번역명 부분 일치, 대소문자 무시) — 스캔 범위를 벗어나지 않는다.

**응답** — scope 무관하게 기존 `Page<FoodSummaryResponse>` 동일(항목·페이징 봉투·페이지 20건).

**무변경**: scope 미지정(=all) 동작·`GET /api/foods`·`POST /api/reviews` 계약 불변. 리뷰 작성 시 스캔 검증 강제는 별도 태스크.

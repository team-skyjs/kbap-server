# API Contract: 스캔 음식 검색·목록 (리뷰 태그 후보)

리뷰 태그 화면은 두 API 로 구성한다(2026-08-19 확정):
- **초기 화면(검색어 입력 전)** — `GET /api/foods/scanned` (스캔 음식 목록, 커서 페이징)
- **검색어 입력 후** — `GET /api/foods/search?scope=scanned` (검색, 무페이징)

## GET /api/foods/scanned — 스캔 음식 목록 (회원 전용)

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `lang` | O | 표시 언어 — 기존 규칙 동일 |
| `cursor` | X | 직전 페이지 nextCursor(Long, 마지막 항목 foodId). 형식 오류·본인 스캔 이력에 없는 foodId 는 400(FOOD-002) |

- 인증 없으면 401(JWT 보호 경로 등록). 본인 스캔 이력 매칭 READY 음식만, 중복 제거·마지막 스캔 시점 내림차순, 한 페이지 20건.
- 커서 페이징: 정렬키가 집계값이라 서버가 커서 foodId 의 last_scanned_at 을 재계산해 (last_scanned_at, food_id) 복합 keyset 으로 절단.
- 응답은 기존 `Page<FoodSummaryResponse>` 동일.

## GET /api/foods/search

**요청**

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `scope` | X | 검색 범위 — `all`(기본, 전체 음식)·`scanned`(본인 스캔 음식). 그 외 값 400(COMMON-002) |
| `keyword` | O | scope 무관 필수(누락·빈/공백 400) — 검색어 입력 전 초기 화면은 이 API 가 아니라 스캔 내역 조회가 담당(2026-08-19 개정) |
| `lang` | O | 표시 언어 — 기존 규칙 동일(누락·빈값 400, 미지원 코드 en 폴백) |
| `cursor` | X | scope=all 전용(직전 페이지 nextCursor). **scope=scanned 는 페이징이 없어 무시된다.** 형식 오류는 scope 공통 400(FOOD-002) |

**scope=scanned 규칙**

- **회원 전용** — 인증 없으면 401(AUTH-003). 보호는 URL 필터가 아니라 컨트롤러 분기가 소유한다(같은 경로의 scope=all 이 비회원 공개라 필터 등록 불가).
- 본인 스캔 이력에 매칭된 READY 음식만, 중복 제거·마지막 스캔 시점 내림차순(재스캔 시 맨 앞). **페이징 없이 매칭 전체를 한 번에 반환**(hasNext 항상 false·nextCursor 항상 null, 2026-08-19 개정 — 검색어 필수라 모수가 작고 집계 정렬 특성상 커서가 DB 비용을 줄이지 못함).
- 삭제·비공개 음식·음식 미매칭 스캔 항목 제외, 스캔 이력 없으면 빈 목록.
- keyword 매칭 규칙은 scope=all 과 동일(한국어명 또는 요청 언어 번역명 부분 일치, 대소문자 무시) — 스캔 범위를 벗어나지 않는다.

**응답** — scope 무관하게 기존 `Page<FoodSummaryResponse>` 동일(항목·페이징 봉투·페이지 20건).

**무변경**: scope 미지정(=all) 동작·`GET /api/foods`·`POST /api/reviews` 계약 불변. 리뷰 작성 시 스캔 검증 강제는 별도 태스크.

# Contract: 관리자 신규 음식 적재

## POST /api/v1/admin/foods

관리자가 한국 음식 메뉴 이름 목록을 제출하면, 기존 food(korean_name)에 없는 이름만 INCOMPLETE 로 적재한다. 멱등 — 같은 목록 재제출 시 created=0 으로 성공.

### 인증/인가

- `Authorization: Bearer <JWT>` 필수. 서명 검증 실패·부재·만료 → **401** (JwtAuthenticationFilter).
- 서명은 유효하나 `role != ADMIN` → **403** `AUTH-008` (AdminAuthorizationInterceptor).

### Request

```json
{
  "koreanNames": ["마라샹궈", "김치찌개", "  ", "마라샹궈", "탕후루"]
}
```

| 필드 | 타입 | 제약 |
|---|---|---|
| koreanNames | array\<string\> | 필수(null 불가), 최대 500건. 각 항목 최대 255자. 서버가 **스캔 입구와 동일한 정규화(NFC·한글만 유지)** 후 빈 항목 제거·중복 제거 — korean_name 은 항상 정규화 상태라는 불변식을 따른다. 위 예시의 실제 판정 대상은 3건 |

### Response 200 — BaseResponse 포맷

```json
{
  "success": true,
  "payload": { "requested": 3, "created": 2, "skipped": 1 },
  "message": null,
  "code": null
}
```

| 필드 | 의미 |
|---|---|
| requested | blank 제거·dedup 후 판정 대상 수 |
| created | 신규 INCOMPLETE 적재 수 |
| skipped | korean_name 기존 존재로 건너뛴 수 (requested = created + skipped) |

- 유효 항목이 0건(빈 배열·전부 blank)이어도 **200** `{requested:0, created:0, skipped:0}` — 실패 아님.
- 적재된 음식 초기값: content_status=INCOMPLETE, spiciness=-1(미조사), avoidance_substances=null(미조사), description="설명 준비 중", 번역 {} — 이후 배치 파이프라인(KB-182~184)이 완성.

### Errors

| status | code | 조건 |
|---|---|---|
| 400 | COMMON-002 | koreanNames null·항목 255자 초과 등 요청 검증 실패 |
| 401 | AUTH-003 / AUTH-004 | 토큰 부재·위조 서명·만료 |
| 403 | AUTH-008 | 유효 토큰이나 role 이 ADMIN 아님(USER 등) |

### 멱등성·동시성

- 같은 목록 재실행: 새 행 0, 200 성공(created=0, skipped=requested).
- 동시 중복 요청: `uq_food_korean_name` + insert-or-ignore upsert 로 각 이름 정확히 1행. `created` 는 **upsert 후 재조회 확정치** — 경합 패배·소프트 삭제 유령으로 실제 생성되지 않은 이름은 skipped 로 집계된다(두 동시 요청의 created 합 = 실제 생성 수).

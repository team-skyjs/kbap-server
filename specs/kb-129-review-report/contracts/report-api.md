# API Contract: 신고 (kb-129)

모든 응답은 `ResponseEntity<BaseResponse<T>>` 봉투. 경로 베이스는 `ApiPaths.V1`. 두 API 모두 인증 필수 — `JwtAuthenticationFilter` include 목록에 `${ApiPaths.V1}/reports` 를 **신규 등록**해야 한다(리뷰 경로는 기등록).

## POST /api/v1/reports — 신고 접수 (신규)

패키지 `com.kbap.api.report` — `ReportController` + `ReportApi`(swagger 전용 인터페이스).

Request (인증: `@AuthMemberId memberId`):

```json
{
  "targetType": "REVIEW",
  "targetId": 42,
  "reason": "SPAM",
  "detail": "광고 링크가 반복 게시됨"
}
```

| 필드 | 타입 | 제약 |
|---|---|---|
| `targetType` | enum `ReportTargetType` | 필수. 이번 범위 `REVIEW` 뿐 |
| `targetId` | number | 필수 |
| `reason` | enum `ReportReason` — `SPAM`·`ABUSE`·`FALSE_INFO`·`SEXUAL`·`OTHER` | 필수 |
| `detail` | string | 선택, 최대 500자 |

Responses:

| 상황 | HTTP | code |
|---|---|---|
| 접수 성공 | 200 | — (`BaseResponse.ok`, payload 없음) |
| 필수 누락·500자 초과·미정의 enum 값 | 400 | `COMMON-*` (기존 validation 공통 처리) |
| 자기 콘텐츠 신고 | 400 | `REPORT-001` |
| 존재하지 않거나 삭제된 대상 | 404 | `REPORT-003` |
| 같은 대상 중복 신고(동시 요청 포함) | 409 | `REPORT-002` |
| 미인증 | 401 | 기존 인증 필터 규약 |

유스케이스(`ReportService.createReport`) 검증 순서: 대상 조회(없으면 404) → 자기 콘텐츠(400) → 중복 선조회(409) → 저장(UNIQUE 위반 → 409 변환).

## GET /api/v1/reviews?foodId= — 음식 리뷰 목록 (변경)

- 컨트롤러 `listFoodReviews` 에 `@AuthMemberId memberId: Long` 추가(경로 인증은 기존과 동일 — 시그니처만 변경, `ReviewApi` 동기화).
- 동작 변경: **호출 회원이 신고한 리뷰를 결과에서 제외**한다. 다른 회원의 결과는 불변.
- 페이지 규약 불변: `Page{items, hasNext, nextCursor}` — 제외로 items 가 20개 미만이어도 재조회하지 않고 `hasNext`·`nextCursor`(마지막 row id) 규약 유지.
- 응답 스키마·기타 파라미터(`countryCode`·`cursor`) 불변.

## 변경 없음 (명시)

- `GET /api/v1/reviews/me` — 자기 리뷰 신고 불가라 제외 대상이 없다(spec Assumptions).
- 음식 상세 평점 요약(`getFoodRatingSummary`) — 전체 리뷰 기준 유지.

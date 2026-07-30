# Contract: 관리자 음식 목록·상세 모달 SSR 페이지

관리자 SSR 페이지는 `/api/v` REST 규약 밖(프레임워크·관리자 경로)이다. 계약 대상은 URL 구조·redirect·템플릿 렌더링 결과다. 인증은 기존 `AdminPageAuthInterceptor` 체계 그대로(무변경).

## GET /admin/foods/list

| 파라미터 | 필수 | 설명 |
|----------|------|------|
| page | X | 1 이상 정수, 기본 1 (기존 유지) |
| detail | X | 음식 id — 있으면 상세 모달 렌더 (기존 유지) |

**렌더링 계약 (변경분)**:

- 목록 각 행 컨테이너에 `id="food-<음식id>"` anchor 존재.
- 각 행의 상세보기 링크 href = `/admin/foods/list?page=<p>&detail=<id>#food-<id>`.
- 모달 닫기 링크 href = `/admin/foods/list?page=<p>#food-<id>`.
- `detail` 지정 + 해당 음식 `imageRef` 존재 시: 모달에 `<img src="<해석된 공개 URL>">` 렌더. 해석 규칙은 `ImageUrls.resolve(kbap.storage.public-base-url, imageRef)` — 절대 URL 이면 그대로, base 미설정이면 ref 원문.
- `imageRef` 부재 시: `<img>` 대신 플레이스홀더 요소 렌더. 이미지 로드 실패 시 클라이언트에서 플레이스홀더로 대체 표시.
- 기존 렌더링(목록·페이지네이션·모달 폼 필드·오류 배너)은 무변경.

## POST /admin/foods/{id}

파라미터 무변경. **redirect 계약 (변경분)** — 모든 분기의 Location 에 `#food-<id>` fragment 추가:

| 결과 | Location |
|------|----------|
| UPDATED | `/admin/foods/list?page=<p>&updated=<id>#food-<id>` |
| NOT_FOUND | `/admin/foods/list?page=<p>&error=not-found#food-<id>` |
| INVALID_NAME | `/admin/foods/list?page=<p>&detail=<id>&error=invalid-name#food-<id>` |
| INVALID_JSON | `/admin/foods/list?page=<p>&detail=<id>&error=invalid-json#food-<id>` |
| DUPLICATE_NAME | `/admin/foods/list?page=<p>&detail=<id>&error=duplicate-name#food-<id>` |

쿼리 파라미터·상태코드(3xx redirect)·처리 로직은 무변경.

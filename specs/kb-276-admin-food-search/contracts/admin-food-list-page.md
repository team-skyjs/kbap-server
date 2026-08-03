# Contract: 관리자 음식 목록 검색

관리자 화면(서버 렌더링)이라 `/api/v1` 규약·`BaseResponse` 봉투 대상이 아니다.

## GET /admin/foods/list

| 파라미터 | 필수 | 설명 |
|----------|------|------|
| `page` | X | 1-base 페이지 번호. 기존과 동일(잘못된 값 → 1) |
| `q` | X | 음식명 검색어. 트림 후 blank 면 전체 목록(파라미터 없음과 동일) |
| `detail` | X | 상세 오버레이 대상 음식 id (기존) |
| `edit` | X | 편집 모드 (기존) |

- `q` 존재 시: `koreanName` 부분 일치(위치 무관 contains) 목록, 정렬 id DESC·페이지 크기 200 유지.
- 화면 내 페이지 이동·상세·닫기·편집·취소 링크는 현재 `q`를 유지한다.
- 결과 0건: 빈 목록 안내 + "전체 목록" 초기화 링크(`q` 제거) 표시.

## POST /admin/foods/{id} (기존 수정 폼)

- hidden input `q` 추가(폼이 현재 검색어를 함께 제출).
- 모든 redirect(`UPDATED`·`NOT_FOUND`·`INVALID_*`·`DUPLICATE_NAME`)가 `q`를 URL 인코딩해 유지한다.
  `q` blank 면 파라미터 생략.

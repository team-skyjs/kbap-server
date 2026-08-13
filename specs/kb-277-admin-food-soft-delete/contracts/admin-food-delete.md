# Contract: 관리자 음식 삭제

관리자 서버 렌더 화면 계약 — `/api/v1` BaseResponse 규약 밖(기존 `/admin/**` 페이지 컨트롤러와 동일).
인증: 기존 `AdminPageAuthInterceptor` 가 `/admin/**` 전체를 보호(이번 변경 없음).

## POST /admin/foods/{id}/delete

음식 1건을 소프트 삭제한다.

**Request** (`application/x-www-form-urlencoded`):

| 파라미터 | 위치 | 필수 | 설명 |
|----------|------|------|------|
| `id` | path | ✔ | 삭제할 음식 id |
| `page` | form | — | 삭제 직전 보던 목록 페이지 번호 (기본 1, 1 미만은 1 로 보정 — 기존 수정 폼과 동일) |

**Response**: 302 redirect

| 결과 | Location |
|------|----------|
| 삭제 성공 | `/admin/foods/list?page={page}&deleted={id}` |
| 미존재·기삭제 | `/admin/foods/list?page={page}&error=not-found` |

앵커 없음(삭제된 row 는 목록에 없음).

## 화면 계약 (food-list.html 상세 패널)

- 상세 패널(비편집 모드)에 **삭제 버튼**(위 POST 폼) 추가.
- 폼 제출 시 네이티브 `confirm()` 확인 단계 필수 — 취소하면 요청 미발생.
- 확인 문구·패널 안내 문구에 명시: 삭제된 음식의 이름은 계속 점유되어 **같은 이름을 다시 시드하면 등록이 조용히 누락**된다.
- `?deleted={id}` 도착 시 삭제 완료 배너 표시(기존 `updated` 배너와 같은 패턴).
- `?error=not-found` 는 기존 목록 에러 배너로 표시.

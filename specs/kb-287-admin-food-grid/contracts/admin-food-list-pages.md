# Contract: 관리자 음식 목록 화면 (SSR)

관리자 세션 인증(`AdminPageAuthInterceptor`) 하의 Thymeleaf 화면 — `/api/v1` REST 규약 대상 아님(비즈니스 API 아님). BaseResponse 봉투 미적용(SSR).

## GET /admin/foods/list

| 파라미터 | 타입 | 필수 | 동작 |
|----------|------|------|------|
| `page` | String | ✕ | `toIntOrNull() ?: 1`, 최소 1 (기존 유지) |
| `q` | String | ✕ | 음식명 부분 일치 검색 (기존 유지) |
| `status` | String | ✕ | **신규.** `FoodContentStatus` 이름과 정확 일치 시 필터 적용. 미일치·미지정 → 필터 없음(전체). 오류 없음 |
| `detail` | Long | ✕ | 해당 음식 상세 **모달** 오픈 (기존 파라미터, 표현만 패널 → 모달) |
| `edit` | Boolean | ✕ | `detail` 과 함께일 때 편집 모드 (기존 유지) |
| `updated` / `deleted` / `error` | | ✕ | 결과 배너 (기존 유지) |

렌더 계약 (템플릿 `admin/food-list.html`):

- 목록은 `.food-grid-viewport`(고정 높이·내부 스크롤) 안의 `.food-grid` 카드 그리드. 카드 = 썸네일(`imageUrl`, 없거나 로드 실패 시 플레이스홀더) + 음식명(한 줄 말줄임) + 상태 배지 + 상세보기 링크.
- 검색 폼은 `q` 인풋 + `status` select(`전체` + 6종) 단일 GET 폼. 페이지네이션·상세보기 링크·모달 내 hidden 필드가 `page`·`q`·`status` 를 모두 스레딩.
- `detail` 지정 + 존재 시 `<dialog class="food-modal">` 렌더, 로드 시 `showModal()`. 닫기 링크 = `detail`(및 `edit`) 제거한 동일 목록 URL. ESC(cancel 이벤트)도 닫기 링크와 동일 동작.
- 모달 내부: 기존 상세 필드 전부, 표시 방식 무변경(읽기 모드 = 입력 `disabled`, 편집 모드 = 입력 활성). JSON 3종은 기존 `<textarea class="json-input">` 그대로 — 편집 시 반드시 제출되어야 한다(누락 시 서버가 빈 값으로 덮어씀).
- 모달 푸터 버튼: 편집(`.btn .btn-neutral`)·취소(`.btn .btn-neutral`)·저장(`.btn .btn-primary`)·삭제(`.btn .btn-danger`) — 공통 규격 + 역할 색.
- 그리드 뷰포트 scrollTop 은 sessionStorage 저장/복원(인라인 JS).

## POST /admin/foods/{id} (수정 — 기존 + status 스레딩)

- form 필드: 기존 전부(`koreanName`·`description`·`spiciness`·`contentStatus`·`imageRef`·JSON 3종·`page`·`q`) + **`status`(hidden, 신규)**.
- 리다이렉트: 기존 결과 분기 유지(성공 `updated=`, 유효성 오류 `detail+edit+error` 재오픈, `not-found` 오류). 쿼리에 `status` 포함. **`#food-{id}` fragment 는 제거**(scrollTop 복원이 위치 유지를 대신함 — KB-259 계약의 목적 승계).

## POST /admin/foods/{id}/delete (수정 — 기존 + status 스레딩)

- form 필드: `page`·`q` + **`status`(hidden, 신규)**. 삭제 확인은 기존 `confirm()` 유지.
- 리다이렉트: 성공 `deleted=`, 실패 `error=not-found`. `status` 포함, fragment 제거.

## 불변 계약 (회귀 금지)

- 앱 사용자 REST API(`/api/v1/**`) 무변경.
- 저장 시 `transitionByContentState()` 호출(KB-260) 유지.
- 소프트 삭제·중복 이름 검증·JSON 유효성 검증 결과 분기 유지.
- 시드·이미지 배치 화면(`/admin/foods/seed`·`/admin/foods/images`) 무변경.

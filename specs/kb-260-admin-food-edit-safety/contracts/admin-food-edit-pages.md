# Page Contract: 관리자 음식 상세 모달 — 읽기 전용 기본·편집 토글·상태 자동 보정

관리자 SSR 페이지 계약(공용 REST `/api/v1/**` 아님 — BaseResponse·ApiPaths 규약 대상 외).
전 경로는 기존 `AdminPageAuthInterceptor` 세션 인증을 그대로 탄다.

## GET /admin/foods/list

| 파라미터 | 필수 | 의미 |
|----------|------|------|
| `page` | X (기본 1) | 목록 페이지 (기존) |
| `detail` | X | 값이 있으면 해당 음식 상세 모달 렌더 (기존) |
| `edit` | X (신규) | `detail` 과 함께 truthy 값이면 편집 모드 렌더. `detail` 없이 단독으로는 무의미(무시) |

### 렌더 계약 — 읽기 전용 모달 (`detail` 있음, `edit` 없음) ← 기본

- 모든 입력 필드(`koreanName`·`contentStatus`·`spiciness`·`imageRef`·`description`·번역 JSON 2종·기피 성분 JSON)가 **`disabled`** 로 렌더된다.
- **저장 폼(POST) 미제출 상태**: 저장 버튼이 렌더되지 않는다.
- **'편집' 링크** 렌더: `@{/admin/foods/list(page, detail, edit=true)}`.
- 기존 요소 유지: 닫기(✕) 링크, version·생성/수정 시각, 오류 배너.

### 렌더 계약 — 편집 모달 (`detail` + `edit`)

- 모든 입력 필드 활성(disabled 없음), 값은 DB 현재 값으로 채움.
- **저장 버튼** 렌더(기존 POST 폼).
- **'취소' 링크** 렌더: `@{/admin/foods/list(page, detail)}` — edit 만 뗀 동일 URL. 서버가 DB 값으로 재렌더하므로 변경 중 값은 버려진다(원값 복원).

### 저장 실패 후 재진입 (기존 리다이렉트 경로 유지 + edit 유지)

- `error=invalid-name|invalid-json|duplicate-name` 리다이렉트는 **편집 모드로** 모달을 다시 연다(`detail` + `edit`) — 관리자가 입력을 이어서 고칠 수 있어야 한다.

## POST /admin/foods/{id} (기존 — 파라미터 계약 무변경)

요청 파라미터·검증(빈 이름/중복 이름/JSON 오류)·리다이렉트 목적지는 기존과 동일. 달라지는 것은 저장 성공 시 서버가 확정하는 `contentStatus` 뿐이다:

| 관리자 선택 상태 | 저장되는 최종 상태 |
|------------------|-------------------|
| `PENDING_REVIEW` 또는 `READY` | 선택값 그대로 (수동 검수 판단 우선) |
| `INCOMPLETE` 또는 `PENDING_IMAGE` | 완성도 재계산: 텍스트 미완 → `INCOMPLETE`, 텍스트 완비·이미지 없음 → `PENDING_IMAGE`, 텍스트 완비·이미지 있음 → `PENDING_REVIEW` |

- 검증 실패 시 상태 보정 없음(아무 값도 저장되지 않음 — 기존과 동일).
- 성공 리다이렉트 `?updated={id}` 후 목록·상세에는 보정된 최종 상태가 표시된다.

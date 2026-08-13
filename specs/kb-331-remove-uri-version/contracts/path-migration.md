# Contract: 경로 이동 + 버전 헤더 필수화

## 헤더 규약

- `/api/**` 전 요청은 `X-API-Version` 필수. 누락 → **400** `{"success":false,"code":"COMMON-002",...}` (BaseResponse 봉투).
- 미지원 버전 값 → 동일하게 400 COMMON-002.
- **유일 예외**: `GET /api/app-version` — 헤더 없이 동작(강제 업데이트 복구 경로).
- 비-API 경로(관리자 콘솔 페이지 `/admin/**`·swagger·actuator)는 헤더 무관.

## 경로 이동 (전 기능 동작·인증 요구 불변)

| 구 경로 (제거) | 새 경로 |
|---|---|
| `/api/v1/members/**` | `/api/members/**` |
| `/api/v1/auth/**` | `/api/auth/**` |
| `/api/v1/scans` (v1 스캔) | `/api/scans` (기존 v2 와 동일 경로 — X-API-Version 으로 분기) |
| `/api/v1/foods/**` | `/api/foods/**` |
| `/api/v1/home/**` | `/api/home/**` |
| `/api/v1/bookmarks/**` | `/api/bookmarks/**` |
| `/api/v1/community/**` | `/api/community/**` |
| `/api/v1/reports` | `/api/reports` |
| `/api/v1/images/**` | `/api/images/**` |
| `/api/v1/blocks/**` (block 리소스) | `/api/blocks/**` |

- 구 `/api/v1/...` 호출은 404 (매핑 소멸). 과도기 이중 매핑·리다이렉트 없음.
- 게스트 열람 예외(GET community posts 목록·단건)·JWT 보호 경로 목록은 새 경로 기준으로 동일하게 유지.
- 이미 무버전인 경로(`/api/scans`(v2)·`/api/reviews`·`/api/ingredients`·`/api/app-version`·`/api/admin/**`)는 경로 불변 — 헤더 필수화만 적용(app-version 제외).

## 외부 소비자 영향

- **iOS 앱**: 새 릴리스부터 새 경로 + 전 요청 헤더 첨부. 기존 배포 앱은 서버 개정 배포 시점부터 동작 불가(강제 업데이트 전제 — plan "배포 순서" 참조).
- **kbap-langchain**: `POST /api/admin/foods/contents` 등 admin 호출에 `X-API-Version: 1.0` 추가 필요 — **kbap 배포 전 선행**(현재 미전송).

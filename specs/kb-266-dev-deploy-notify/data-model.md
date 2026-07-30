# Data Model: KB-266

DB·엔티티 변경 없음. 산출물(artifact) 구조만 정의한다.

## 릴리즈 (GitHub Release)

| 필드 | 값 | 규칙 |
|------|-----|------|
| tag | `dev-YYYYMMDD-<short-sha>` / `prod-YYYYMMDD-<short-sha>` | 날짜 KST, short-sha 7자리, target = 배포 커밋 |
| prerelease | dev=true, prod=false | |
| title | `DEV · YYYY-MM-DD · <short-sha>` / `PROD · ...` | prod 는 본문 상단에 "배포 시작 기준" 명시 |
| body | GitHub `--generate-notes` 자동 생성 | `--notes-start-tag <직전 같은 환경 태그>` 로 범위 고정 |
| assets | `openapi.json`, `openapi-diff.md` | 초기 릴리즈는 diff 없이 스냅샷만 + "초기 OpenAPI 스냅샷" 표기 |

**상태 규칙**: 발행 전 `gh release view <tag>` 존재 확인 → 있으면 skip(멱등). 재배포(build=false) 경로는 릴리즈 미생성.

## OpenAPI 스냅샷 (`openapi.json`)

- 생성: `OpenApiSnapshotTest` 가 MockMvc 로 `GET /v3/api-docs` 응답을 `api/build/openapi.json` 에 기록.
- 정규화: diff 전 `servers` 등 환경 종속 필드 jq 제거.
- 수명: 릴리즈 asset 으로 영구 보관 — 다음 배포의 baseline.

## diff 요약 (슬랙 페이로드용 중간 값)

oasdiff changelog(json) 에서 추출: `추가 수` · `변경 수` · `삭제 수` · `breaking 수` · 엔드포인트 목록(`메서드 경로 — 한 줄 설명`). 전부 0 이면 "API 변경 없음".

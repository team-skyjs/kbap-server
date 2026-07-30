# Contract: release-notes.yml (workflow_call)

## Inputs

| 이름 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `environment` | string | ✔ | `dev` \| `prod` — 태그 접두어·문구 결정 |
| `prerelease` | boolean | ✔ | dev=true, prod=false |
| `sha` | string | ✔ | 배포 커밋 SHA(태그 target·스냅샷 체크아웃 기준) |
| `redeploy` | boolean | ✔ | true(=build=false 재배포)면 release 잡 skip, notify 는 "재배포" 메시지 |
| `deploy_result` | string | ✔ | 호출측 deploy 잡 결과(`success`/`failure`) — notify 분기 |

## Secrets

| 이름 | 설명 |
|------|------|
| `SLACK_RELEASE_WEBHOOK_URL` | Slack Incoming Webhook. 로그 미노출(마스킹은 GitHub 기본) |

## Permissions (release 잡에만)

- `contents: write` — 태그·릴리즈 생성
- 그 외 기본(read)

## release 잡 단계 계약

1. `actions/checkout` — `ref: <sha>`
2. 스냅샷 생성: `./gradlew :api:test --tests '*OpenApiSnapshotTest*'` → `api/build/openapi.json`
3. baseline: `gh release list` 에서 `<env>-*` 최신 태그 → asset `openapi.json` 다운로드. 없으면 초기 스냅샷 모드
4. oasdiff(버전 고정 다운로드, 체크섬 검증): changelog markdown → `openapi-diff.md`, json → 카운트/목록 추출(출력으로 노출)
5. 발행(멱등): `gh release view <tag>` 없을 때만 발행. 본문은 직접 조립 — 머리말(prod "배포 시작 기준"/초기 스냅샷) → generate-notes API 의 PR 목록 → **`## API 변경 상세`(openapi-diff.md 본문 인라인, 60k자 절단 시 첨부 안내)** — 후 `gh release create <tag> --target <sha> --notes-file [--prerelease]` + asset 업로드
6. 실패 시 원인을 `$GITHUB_STEP_SUMMARY` 에 기록

## notify 잡 계약 (`needs: [release]`, `if: always()`)

- deploy 성공 + release 성공 → 성공 메시지([slack-message.md](slack-message.md))
- deploy 성공 + release 실패/스킵(재배포) → "릴리즈 노트 생성 실패(배포는 성공)" / "재배포" 메시지
- deploy 실패 → 실패 메시지(워크플로 실행 링크)
- 슬랙 curl 실패는 잡 실패로만 남기고 아무것도 롤백하지 않는다

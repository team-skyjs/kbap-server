# Quickstart: KB-266 설정·검증

## 사전 준비 (1회, 사람 작업)

1. 슬랙에서 알림 채널 생성(예: `#kbap-releases`) → Incoming Webhook 발급.
2. GitHub repo Settings → Secrets and variables → Actions → **Secret `SLACK_RELEASE_WEBHOOK_URL`** 등록(dev·prod environment 공용이면 Repository secret).

## 로컬 검증

```bash
./gradlew :api:test --tests '*OpenApiSnapshotTest*'   # api/build/openapi.json 생성 확인
jq '.paths | keys | length' api/build/openapi.json     # 경로 수 > 0
```

## 배포 검증 시나리오 (검증 기준 = 사용자 요구 8)

| # | 시나리오 | 기대 |
|---|----------|------|
| 1 | API 변경 포함 커밋을 develop 머지 | dev prerelease `dev-YYYYMMDD-<sha>` 발행, asset 2종, 슬랙에 변경 목록+링크 |
| 2 | API 무관 커밋 머지 | 슬랙 "API 변경 없음" 명시 |
| 3 | 같은 워크플로 Re-run | 릴리즈 중복 생성 0 (기존 태그 재사용) |
| 4 | main 머지 | prod 정식 릴리즈, 직전 `prod-*` 이후 PR 전부 본문에 나열, 슬랙 "배포 시작" 문구 |
| 5 | `workflow_dispatch` + image_tag 재배포 | 릴리즈 미생성, 슬랙 "재배포" 알림 |
| 6 | 최초 실행 | "초기 OpenAPI 스냅샷" 표기, diff 생략 |
| 7 | 웹훅 시크릿 제거 후 배포 | 배포·릴리즈는 정상, notify 잡만 실패(성공 판정 불변) |
| 8 | 로그 검색 | 웹훅 URL·토큰 노출 0 (GitHub 시크릿 마스킹) |

## 트러블슈팅

- 릴리즈 잡 실패 원인은 해당 run 의 **actions summary** 에 기록된다.
- oasdiff 다운로드 실패 시: 바이너리 버전·체크섬은 `release-notes.yml` 상단 env 에 고정 — 버전 갱신은 그 두 값만 수정.
- 스냅샷 테스트가 로컬에서 실패하면 Docker(Testcontainers) 기동 여부부터 확인.

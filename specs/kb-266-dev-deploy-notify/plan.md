# Implementation Plan: dev/prod 배포 GitHub Release 자동 발행 + 슬랙 API 변경 알림

**Branch**: `kb-266-dev-deploy-notify` | **Date**: 2026-07-30 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-266-dev-deploy-notify/spec.md`

## Summary

dev(develop→EC2+SSM)·prod(main→ECS blue/green) 배포마다 GitHub Release 를 자동 발행하고(태그 `dev|prod-YYYYMMDD-<short-sha>`, 본문 GitHub 자동 생성, `openapi.json`·`openapi-diff.md` 첨부), 슬랙 Incoming Webhook 으로 API 변경 요약(oasdiff 기계 비교 — LLM 미사용)과 Release 링크를 보낸다. OpenAPI 문서는 배포 서버가 아니라 **CI 러너에서 배포 커밋으로 직접 생성**(`OpenApiSnapshotTest` — 기존 Testcontainers 통합테스트 인프라 재사용)해 prod "배포 시작까지만 성공" 의미(KB-242)와 충돌하지 않는다. 결정 근거는 [research.md](research.md) R1~R8.

## Technical Context

**Language/Version**: GitHub Actions YAML + bash(jq·gh·curl — 러너 내장) / Kotlin 2.3(스냅샷 테스트 1개)

**Primary Dependencies**: springdoc(기존 — `/v3/api-docs`), oasdiff(버전 고정 바이너리, CI 다운로드), gh CLI(러너 내장), Slack Incoming Webhook

**Storage**: GitHub Releases(스냅샷 baseline 보관 겸용). S3·DB 미사용

**Testing**: 스냅샷 테스트는 Kotest BehaviorSpec(기존 인프라). 워크플로 자체는 단위테스트 불가 — [quickstart.md](quickstart.md)의 실배포 검증 절차로 확인

**Target Platform**: GitHub Actions ubuntu-latest 러너

**Project Type**: CI/CD 파이프라인 확장(앱 런타임 코드 무변경)

**Performance Goals**: 배포 종료 후 슬랙 도착 ≤ 5분(SC-001 — 릴리즈 잡의 스냅샷 생성 3~5분 포함, 배포 잡 자체는 지연 0)

**Constraints**: 기존 deploy 잡 스텝 무변경(출력 노출만 허용) · release 잡에만 `contents: write` · 알림/릴리즈 실패가 배포 판정에 무영향 · 시크릿 로그 미노출

**Scale/Scope**: 워크플로 3파일(신규 1 + 기존 2 잡 추가) + 테스트 1개. staging·batch 범위 밖

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | 유일한 앱 코드가 테스트 자체(`OpenApiSnapshotTest`) — 작성 시 Red(파일 미존재 assert) 확인 후 구현 없이 통과 불가 구조는 아니므로, "스냅샷 파일이 유효한 OpenAPI JSON 인지" assert 를 테스트 본문에 포함해 자기 검증한다. 워크플로 YAML 은 테스트 프레임워크 대상이 아님 — quickstart 검증 기준으로 대체(감수, 헌법 위반 아님: 소스 코드가 아니라 파이프라인 구성) |
| II. Bounded Contexts | PASS | 도메인 코드 무변경 |
| III. Layered Dependency Direction | PASS | 모듈 의존 무변경(테스트는 `:api` test 소스셋) |
| IV. Persistence Ownership | PASS | 영속 무변경 |
| V. Language Policy | PASS | 무관 |

**Post-Phase 1 재평가**: 위반 없음 — Complexity Tracking 불요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-266-dev-deploy-notify/
├── plan.md              # 이 파일
├── research.md          # R1~R8 결정 기록
├── data-model.md        # 산출물(릴리즈·스냅샷) 구조
├── quickstart.md        # 시크릿 설정·검증 절차
├── contracts/
│   ├── release-format.md      # 태그·본문·asset 계약
│   └── slack-message.md       # 슬랙 메시지 계약
└── tasks.md             # /speckit-tasks 산출(이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
.github/workflows/
├── deploy-dev.yml       # [수정] deploy 잡에 outputs(build·image_tag) 노출 + release 호출 잡 추가
├── deploy-prod.yml      # [수정] 동일 패턴(prerelease=false, "배포 시작" 문구)
└── release-notes.yml    # [신규] workflow_call 재사용 워크플로
    ├── job release      #   스냅샷 생성(gradle) → baseline 다운로드 → oasdiff → gh release create
    └── job notify       #   needs:[release] if:always() — 슬랙 발송(성공 요약/실패 알림)

api/src/test/kotlin/com/kbap/api/openapi/
└── OpenApiSnapshotTest.kt   # [신규] MockMvc GET /v3/api-docs → api/build/openapi.json 기록 + 유효성 assert
```

**Structure Decision**: 공통 로직은 `release-notes.yml`(workflow_call) 한 곳 — dev/prod 는 `environment`·`prerelease` 입력만 다르게 호출한다. 기존 deploy 잡의 스텝·성공 의미(dev 헬스체크 통과 / prod 배포 시작 확인)는 손대지 않고, 후속 잡이 `needs:` 로 이어붙는다. 재배포(build=false)는 release 잡을 skip 하고 notify 만 "재배포" 메시지를 보낸다.

## Complexity Tracking

> 위반 없음 — 해당 없음.

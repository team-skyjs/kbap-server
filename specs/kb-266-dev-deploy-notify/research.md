# Research: KB-266 dev/prod 배포 Release 자동 발행 + 슬랙 API 변경 알림

**Date**: 2026-07-30 · 브레인스토밍(KB-266 DoD 1번) 결과를 결정 기록으로 정리

## R1. API 변경 산출 방식

- **Decision**: OpenAPI 문서 기계 비교(oasdiff). LLM·PR 본문 파싱 미사용.
- **Rationale**: 프론트가 슬랙 요약을 신뢰해야 하는 기능이다(SC-002 — 누락 0). springdoc 이 컨트롤러 코드에서 OpenAPI 를 자동 생성하므로 스냅샷 비교는 결정적이고 API 외형을 100% 커버한다. LLM 요약은 누락·환각을 잡을 수 없고, PR 본문 파싱은 작성자 규율에 의존한다.
- **Alternatives considered**:
  - LLM 이 코드 diff 요약 — 인프라 최소지만 정확도 보장 불가, LLM 키 시크릿 추가. 기각.
  - PR 템플릿 `## API 변경` 섹션 결정적 파싱 + oasdiff 하이브리드(v1 설계) — 정확하지만 PR 템플릿 변경·파싱·본문 조립 비용. 사용자 결정으로 v2 단순화(Release 본문 = GitHub 자동 생성)에 밀려 제외.
  - LLM 이 oasdiff 결과 윤문 — 입력은 정확하나 얻는 게 문장 다듬기뿐. 기각.

## R2. 배포 커밋과 정확히 일치하는 OpenAPI 문서 확보

- **Decision**: 배포된 서버에서 가져오지 않고 **CI 러너에서 배포 커밋을 체크아웃해 직접 생성**한다 — `:api` 에 `OpenApiSnapshotTest`(BehaviorSpec, 기존 통합테스트 인프라: Testcontainers MySQL + Flyway on + seam 페이크) 를 추가하고 MockMvc 로 `GET /v3/api-docs` 응답을 `api/build/openapi.json` 에 기록. 릴리즈 잡이 `./gradlew :api:test --tests '*OpenApiSnapshotTest*'` 로 실행.
- **Rationale**:
  - **prod 충돌 해소가 결정적 근거**: prod 성공 = "블루/그린 배포 시작 확인"까지(KB-242, deploy-prod.yml 주석). 그 시점의 prod `/v3/api-docs` 는 아직 구버전이라 서버 회수는 오답이다. CI 생성은 서버 상태와 무관하게 커밋과 1:1.
  - dev 도 같은 경로로 통일 — 환경 분기 제거.
  - 실현 가능성 검증됨: `build.yml` 이 PR 마다 GitHub 러너에서 동일 통합테스트(전체 컨텍스트 부팅)를 이미 돌린다. 신규 의존성 0(springdoc 공식 패턴).
- **Alternatives considered**:
  - dev 는 SSM 으로 배포 서버 `/v3/api-docs` 회수 — dev 만 되고 prod 불가, 환경 분기 발생. 기각.
  - springdoc-openapi-gradle-plugin(forkedSpringBootRun) — 실 DB/설정 필요, 플러그인 의존성 추가. 기각.
  - prod 는 dev/staging 스냅샷 재사용 — main 머지 커밋 SHA 가 develop 과 달라 SHA 검증이 복잡. 기각.
- **주의**: 생성 문서의 `servers` 등 환경 종속 필드는 diff 전에 jq 로 제거(정규화)한다.

## R3. 비교 기준(baseline) 보관

- **Decision**: 같은 환경 직전 GitHub Release 의 `openapi.json` asset. `gh release list` 로 `dev-*`/`prod-*` 최신을 찾아 내려받는다. 없으면 "초기 OpenAPI 스냅샷" 처리(diff 생략).
- **Rationale**: 릴리즈가 스냅샷의 보관처를 겸한다 — S3 버킷·경로 관리 불필요, "직전 릴리즈와 비교" 정의와 1:1, dev/prod 기준점 분리가 태그 접두어로 자연 해결.
- **Alternatives considered**: S3(별도 버킷·수명주기 관리 필요), 전용 브랜치 커밋(이력 오염) — 모두 기각.

## R4. diff 도구

- **Decision**: [oasdiff](https://github.com/oasdiff/oasdiff) — 버전 고정 단일 Go 바이너리를 릴리즈 잡에서 다운로드. `changelog` 출력(마크다운 → `openapi-diff.md`, json → 카운트·엔드포인트 목록 추출).
- **Rationale**: OpenAPI 의미 비교(경로·메서드·스키마·breaking 판정)를 텍스트 grep 없이 제공하는 검증된 표준 도구. 러너 상주 의존성 아님(CI 다운로드).
- **Alternatives considered**: openapi-diff(Java, 무겁고 JVM 기동) · 자작 jq 비교(스키마 의미 비교 재발명) — 기각.

## R5. Release 태그·멱등성

- **Decision**: `dev-YYYYMMDD-<short-sha>`(prerelease) / `prod-YYYYMMDD-<short-sha>`(정식), `--target <배포 SHA>`. 발행 전 `gh release view <tag>` 로 존재 확인 — 있으면 skip(멱등). 날짜는 워크플로 실행 시각(UTC 아님 — KST 고정) 기준.
- **Rationale**: 사용자 확정 요구. 같은 날 다중 배포는 short SHA 로 구분, 재실행은 view-then-create 로 중복 차단.

## R6. 재배포(`image_tag` 수동 입력, build=false) 처리

- **Decision**: 릴리즈를 만들지 않는다. 슬랙에 "재배포" 알림만 발송.
- **Rationale**: 코드 불변이므로 새 릴리즈는 중복 보고다. 다른 날 재배포하면 날짜가 달라져 새 태그가 생기는 문제(멱등성 취지 위반)도 함께 차단된다.

## R7. 워크플로 구조·권한·실패 격리

- **Decision**: 공통 로직은 재사용 워크플로 `.github/workflows/release-notes.yml`(workflow_call — inputs: environment/prerelease 등, secret: webhook). `deploy-dev.yml`·`deploy-prod.yml` 은 기존 deploy 잡 뒤에 호출 잡만 추가(기존 스텝 무변경, build/tag 출력만 노출). release 잡에만 `contents: write` 부여. 슬랙 발송은 `if: always()` 별도 잡 — 릴리즈·슬랙 실패가 배포 판정에 무영향, 원인은 `$GITHUB_STEP_SUMMARY` 기록.
- **Rationale**: 사용자 요구(중복 구현 금지·최소 권한·잡 분리·기존 의미 보존) 그대로.

## R8. 슬랙 발송

- **Decision**: Slack Incoming Webhook + `curl`(러너 내장). Secret 이름 `SLACK_RELEASE_WEBHOOK_URL`(기존 슬랙 시크릿 없음을 확인 — 신규). 메시지 = 환경·날짜·SHA·API 요약(카운트+엔드포인트 목록, 없으면 "API 변경 없음")·Release 링크. prod 는 "배포 시작" 문구. 배포 실패 시 실패 알림(`if: failure()`, 실행 링크 포함).
- **Alternatives considered**: slackapi/slack-github-action — 액션 의존성 추가 대비 이득 없음(단순 webhook POST). 기각.

# Implementation Plan: 브랜치별 배포 자동화 — develop/staging 푸시 시 EC2 컨테이너 배포, main 병합 시 ECS 블루그린

**Branch**: `kb-172-deploy-automation` | **Date**: 2026-07-20 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-172-deploy-automation/spec.md`

## Summary

수동 배포(로컬 빌드→ECR push→EC2 접속 docker 명령)를 브랜치 푸시만으로 자동화한다. 환경별 워크플로 3파일 — `deploy-dev.yml`(develop→공용 EC2 `api-dev` :8080), `deploy-staging.yml`(staging→`api-staging` :8081), `deploy-prod.yml`(main→ECS 네이티브 블루/그린, CodeDeploy 미사용). 공통 흐름: 멀티스테이지 Dockerfile 로 linux/amd64 이미지 빌드 → ECR `kbap-api` push(태그는 환경별 — dev=git sha, staging=`<버전>-rc`, prod=`<버전>`, 버전 단일 출처는 루트 `VERSION` 파일·`latest` 미사용) → 환경별 배포(dev/staging 은 SSM Run Command 로 컨테이너 교체+스크립트 내 헬스체크, prod 는 태스크정의 리비전 갱신+`update-service` 로 블루/그린 트리거+`wait services-stable`). 인증은 GitHub OIDC 로 환경별 IAM 역할 assume(신뢰 정책 `sub`=environment 조건으로 교차 배포 차단), 값은 GitHub Environments variables(secret 0개 — 런타임 비밀은 EC2 env-file/ECS 태스크정의 소유). 롤백은 `workflow_dispatch` + `image_tag` 입력으로 이전 태그(sha/버전) 재배포. **애플리케이션 코드·설정(yml)·DB 0줄** — 저장소 변경은 워크플로 3파일 + `VERSION` 파일뿐이고, AWS 측 구성(OIDC provider·IAM 역할 3개·Environments)은 quickstart 런북으로 수행한다.

## Technical Context

**Language/Version**: GitHub Actions workflow YAML (러너 `ubuntu-latest`) + bash. 애플리케이션 스택(Kotlin/Boot)은 무변경.

**Primary Dependencies**: `actions/checkout`, `aws-actions/configure-aws-credentials@v4`(OIDC), `aws-actions/amazon-ecr-login@v2`, AWS CLI v2(러너 내장 — ssm/ecs/deploy 하위 명령), 기존 `Dockerfile`(멀티스테이지 Gradle bootJar).

**Storage**: N/A — DB·Flyway·엔티티 변경 0. 이미지 저장소는 기존 ECR `kbap-api`(git sha 태그).

**Testing**: JVM 테스트 표면 없음(research R9, KB-169 선례). `actionlint` 정적 검사 + 환경별 실배포 1회 검증(quickstart 런북, DoD).

**Target Platform**: GitHub-hosted runner(amd64) → 배포 대상: 공용 EC2(docker 컨테이너 2개, SSM 관리) + prod ECS(네이티브 블루/그린, CodeDeploy 미사용).

**Project Type**: CI/CD 인프라(배포 파이프라인) — 소스 코드 변경 없음.

**Performance Goals**: 푸시 후 사람 조작 0회로 배포 완료(SC-001). 빌드 캐시 최적화는 범위 밖(R2 — 문제 시 후속).

**Constraints**: SSH 키 금지(SSM 만), 장기 액세스 키 금지(OIDC 만), dev/staging↔prod 교차 배포 IAM 수준 차단, 헬스체크 미통과 = 배포 실패(무한 대기 금지), 같은 EC2 의 이웃 컨테이너 비간섭, GitHub secrets 0개.

**Scale/Scope**: 신규 워크플로 3파일(각 60~90줄) + `VERSION` 파일 1줄 + 런북 1개. 기존 `build.yml`(PR CI) 불변. `:app:batch` 배포는 범위 밖.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | **통과(정당화)** | 프로덕션 코드·설정 0줄 — Kotest 가 실행할 테스트 표면이 없다(워크플로 yml 은 JVM 밖). KB-169 선례에 따라 yml 복창 가드 테스트는 두지 않고, 검증은 배포마다 실행되는 헬스체크 게이트(R3·R4) + 환경별 실배포 1회(quickstart 런북)로 대체한다. |
| II. Bounded Contexts | 해당 없음 | 도메인 모듈 무접촉. |
| III. Layered Dependency Direction | 해당 없음 | 모듈 그래프 무변경. |
| IV. Persistence Encapsulation | 해당 없음 | 엔티티·리포지토리 무접촉. |
| V. Domain Content Language Policy | 해당 없음 | 콘텐츠 무접촉. |

**Post-design re-check**: Phase 1 산출물(워크플로 설계·런북)도 저장소 코드 표면을 늘리지 않음 — 판정 불변, 게이트 통과.

## Project Structure

### Documentation (this feature)

```text
specs/kb-172-deploy-automation/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 결정 9건(R1~R9)
├── quickstart.md        # AWS 측 구성 런북 + 환경별 검증·롤백 절차
└── tasks.md             # Phase 2 (/speckit-tasks 가 생성)
```

data-model.md·contracts/ 없음 — 엔티티·API 계약 변경 0 (KB-169 선례).

### Source Code (repository root)

```text
.github/workflows/
├── build.yml            # 기존 PR CI — 불변
├── deploy-dev.yml       # 신규: develop 푸시 → ECR push → SSM 으로 api-dev(:8080) 교체
├── deploy-staging.yml   # 신규: staging 푸시 → ECR push → SSM 으로 api-staging(:8081) 교체
└── deploy-prod.yml      # 신규: main 푸시 → ECR push → 태스크정의 리비전 → update-service 블루/그린
Dockerfile               # 기존 — 불변(빌드 정의 소유, R2)
VERSION                  # 신규: 릴리스 버전 단일 출처(1줄, 예: 1.0 — staging/prod 이미지 태그 근원, R2)
```

**Structure Decision**: 환경별 3개 독립 워크플로(R1 — Jira 확정: 실패 추적·권한 격리). dev/staging 은 동일 구조에 environment variables 값만 다름. 공용 로직의 reusable workflow 승격은 소비자가 늘 때(YAGNI). 애플리케이션 소스 트리는 무접촉.

### 워크플로 공통 골격 (dev/staging)

```text
on: push(해당 브랜치) + workflow_dispatch(input: image_tag — 롤백용, R8)
concurrency: deploy-<env>, cancel-in-progress: false (R7)
permissions: id-token: write, contents: read (R5)
jobs.deploy (environment: <env>):
  1. checkout
  2. configure-aws-credentials (OIDC, vars.AWS_ROLE_ARN)
  3. ecr-login
  4. [image_tag 미지정 시] docker build --platform linux/amd64 + push
     (태그: dev=git sha, staging=$(cat VERSION)-rc, prod=$(cat VERSION) — R2)
  5. ssm send-command → pull/stop/rm/run(--env-file, -p <port>:8080) + 헬스체크 루프 (R3)
  6. ssm wait command-executed + get-command-invocation (실패 전파, FR-010)
```

prod 는 5–6 대신: describe-services → describe-task-definition → 이미지만 교체한 리비전 등록 → `ecs update-service --task-definition <new>`(블루/그린 트리거) → `ecs wait services-stable` (R4). 블루/그린 전략·타깃그룹·bake 는 서비스에 사전 구성(인프라 소유), `--deployment-configuration` 미전달(덮어쓰기 방지).

## Complexity Tracking

> 위반 없음 — 표 생략.

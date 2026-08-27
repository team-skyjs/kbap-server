# Implementation Plan: 배치 잡 원격 트리거 — ECS Exec 활성화

**Branch**: `kb-374-batch-ecs-exec` | **Date**: 2026-08-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-374-batch-ecs-exec/spec.md`

## Summary

배치 앱의 잡 트리거(`POST /internal/batch/jobs`)는 클러스터 내부에서만 접근 가능하고 인증이 없어, 클러스터 밖(홈서버 젠킨스)에서 잡을 실행할 수 없다. **ECS Exec**(`aws ecs execute-command`)를 배치 서비스에 켜서, 운영자가 AWS 자격증명만으로 배치 컨테이너 안에서 `curl localhost:8080/...` 을 실행하는 통로를 만든다 — 인바운드 포트 개방 0, 추가 비용 0. Terraform 변경 두 곳(서비스 플래그·태스크 역할 ssmmessages 권한) + 환경별 운영 IAM 사용자(권한을 해당 클러스터의 `batch` 컨테이너로 한정) + 원격 실행 스크립트 `iac/scripts/batch-job.sh` + README 절차. 앱 코드는 변경하지 않는다.

## Technical Context

**Language/Version**: Terraform ≥ 1.7 / AWS provider ~> 5.x (기존 `iac/terraform`), Bash(운영 스크립트). 앱(Kotlin/Spring Batch) 코드 무변경.

**Primary Dependencies**: AWS ECS Exec(SSM Session Manager 채널) · 기존 모듈 `iac/terraform/modules/ecs-environment` (`batch.tf`·`iam.tf`) · 호출 측 AWS CLI v2 + **Session Manager plugin**.

**Storage**: N/A — 새 데이터 없음. 잡 실행 이력은 배치 앱이 이미 `BATCH_*` 메타테이블에 영속(FR-002 는 기존 조회 엔드포인트 재사용).

**Testing**: `terraform validate`/`fmt`(정적) + `terraform plan` 검토 + dev apply 후 **실행 가능한 검증 절차**(quickstart — exec 에이전트 RUNNING 확인 → 잡 트리거 202 → 실행 조회 → 교차 환경·타 컨테이너 거부 확인). 앱 테스트(`BatchJobTriggerControllerTest`)는 무변경으로 그린 유지.

**Target Platform**: AWS ap-northeast-2, ECS on EC2(bridge 모드, ECS 최적화 AL2023 AMI), dev(`kbap-dev-ecs-*`) 먼저 → prod 동일 모듈.

**Project Type**: 인프라 변경(IaC) + 운영 스크립트/문서.

**Performance Goals**: 잡 실행 지시 → 실행 ID 수신 1분 이내(SC-001). exec 세션 수립은 통상 수 초.

**Constraints**: 배치 트리거 포트 SG 규칙 무변경(FR-003) · 권한은 환경·컨테이너 단위 최소(FR-004/005) · 태스크 정의는 `ignore_changes` 로 배포 소유 — Exec 플래그는 **서비스** 속성이라 이 경계를 건드리지 않음 · 추가 비용 0(SC-004).

**Scale/Scope**: 환경 2개(dev·prod), 배치 태스크 환경당 1개, 운영 자격증명 환경당 1개. 젠킨스 설치·파이프라인은 범위 밖.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Test-First** — 앱 코드 변경이 없어 Kotest 대상이 없다. IaC 는 (1) `terraform validate` 정적 게이트, (2) dev apply 후 quickstart 의 검증 절차를 **구현 전에 확정해 두고 그 절차로 Green 을 판정**(실패 조건까지 명시 — 교차 환경 거부·타 컨테이너 거부). 기존 앱 테스트 그린 유지. **통과(정당화 기록)**.
- **II. Bounded Contexts** — 도메인·패키지 변경 없음. **통과**.
- **III. Layered Dependency Direction** — 모듈 의존 변경 없음. **통과**.
- **IV. Persistence Ownership** — 스키마·엔티티 변경 없음. **통과**.
- **V. Language Policy** — 해당 없음. **통과**.
- **Additional Constraints** — 외부 호출/트랜잭션 무관. 배치 트리거의 "접수 즉시 202 + 폴링" 의미를 원격에서도 유지(FR-007). **통과**.

Post-design 재확인: Phase 1 산출물이 앱 코드를 건드리지 않고(스크립트·tf·문서만), 포트 개방 없음·권한 최소화가 contracts 에 고정됨 — 게이트 유지.

## Project Structure

### Documentation (this feature)

```text
specs/kb-374-batch-ecs-exec/
├── plan.md              # 이 문서
├── research.md          # Phase 0 — 통로 선택·권한 범위·클라이언트 전제·롤아웃 순서
├── data-model.md        # Phase 1 — 권한 경계 모델(새 영속 데이터 없음)
├── quickstart.md        # Phase 1 — dev 적용·검증 절차(= 테스트 절차)
├── contracts/
│   ├── remote-job-run.md      # batch-job.sh CLI 계약 + 원격 경로에서의 트리거 응답 계약
│   └── operator-iam-policy.md # 환경별 운영 IAM 정책 경계
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
iac/terraform/modules/ecs-environment/
├── batch.tf         # aws_ecs_service.batch 에 enable_execute_command = true
├── iam.tf           # batch_task 정책에 ssmmessages 4개 액션 statement
│                    # + aws_iam_user.batch_operator + 인라인 정책(ecs:ExecuteCommand — 이 클러스터·batch 컨테이너 한정)
├── outputs.tf       # batch_operator_user_name 출력(액세스 키는 사람이 콘솔에서 발급)
└── variables.tf     # (필요 시) 운영 사용자 생성 토글 — 기본 true
iac/scripts/
└── batch-job.sh     # 원격 잡 실행/조회 래퍼: run <env> <jobName> | status <env> <executionId>
iac/terraform/README.md   # "배치 잡 원격 실행" 절 추가(전제: Session Manager plugin, 프로필 2개)
Dockerfile.batch          # curl 부재 시에만 런타임 스테이지에 추가(quickstart 1단계 확인 후)
```

**Structure Decision**: 기존 `ecs-environment` 모듈 안에서만 변경한다 — 새 모듈·새 스택 없음. 운영 사용자·정책도 모듈이 소유해 "환경 = 모듈 1호출" 원칙을 유지하고, dev/prod 권한 분리가 코드로 보장된다(tfvars 로 환경명만 달라짐). 액세스 키는 Terraform state 에 남기지 않기 위해 사람이 발급한다.

## Complexity Tracking

> 헌법 위반 없음 — 기록 대상 없음.

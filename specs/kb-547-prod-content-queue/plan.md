# Implementation Plan: prod 콘텐츠 생성 요청 큐 분리

**Branch**: `kb-547-prod-content-queue` | **Date**: 2026-09-12 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-547-prod-content-queue/spec.md`

## Summary

dev·prod 배치가 같은 SQS 큐 `kbap-generate-content-queue` 로 콘텐츠 생성 요청을 발행해, prod 요청이 dev Lambda 로 흘러간다. prod 전용 큐 `kbap-prod-generate-content-queue` + DLQ `kbap-prod-generate-content-dlq` 를 콘솔에서 만들고(표준 큐·보관 14일·가시성/재시도는 기존과 동일·SSE-SQS), 로컬 `prod.tfvars` 의 `food_content_queue_name` 을 바꾼다. 배치 태스크 정의는 `ignore_changes = [container_definitions]` 라 일반 apply 로는 큐 URL 이 안 바뀌므로 **`-replace` 로 리비전을 재등록한 뒤 `deploy-batch-prod.yml` 을 현재 이미지 태그로 `workflow_dispatch`** 해 롤링한다. apply 순간 배치 롤의 발행 권한이 새 큐 ARN 으로 바뀌어, 롤링 전 구 태스크의 발행은 거부되고 outbox 는 PENDING 으로 남아 다음 정시에 새 큐로 재발행된다 — 전환 창에도 dev 로 새지 않는다(fail-closed). 저장소 변경은 `prod.tfvars.example`·README 한 줄뿐, 앱 코드 무변경.

## Technical Context

**Language/Version**: Terraform ≥ 1.7 / AWS provider ~> 6.0 (기존 `iac/terraform`). 앱(Kotlin/Spring Batch) 무변경.

**Primary Dependencies**: AWS SQS(표준 큐, 콘솔 생성 — 테라폼은 `data "aws_sqs_queue"` 로 이름 조회) · 모듈 `modules/ecs-environment` 의 `data.tf`(큐 조회)·`batch.tf`(`FOOD_CONTENT_QUEUE_URL` env)·`iam.tf`(배치 롤 `sqs:SendMessage` 를 큐 ARN 으로 한정) · CI `deploy-batch-prod.yml`(최신 리비전 복제 + 이미지 교체 롤링).

**Storage**: N/A — 스키마 변경 없음. prod `food_content_outbox`(PENDING→SENT) 는 검증 대상으로만 읽는다(읽기 전용).

**Testing**: 앱 테스트 대상 없음(설정만 바꾸는 인프라 변경). 게이트는 (1) `terraform plan` 결과 모양 — `1 to add, 1 to change, 1 to destroy`(태스크 정의 교체 + 배치 롤 정책 in-place) 외 변경 0, (2) quickstart 의 실측 검증 절차(태스크 env·배치 로그·outbox SENT 건수 = 새 큐 메시지 수·배치 롤 정책 ARN).

**Target Platform**: AWS 팀 계정 `118178010621`·`ap-northeast-2`, prod ECS(`kbap-prod-ecs-*`), terraform workspace `prod`, 프로필 `kbap-infra`.

**Project Type**: 운영 절차(콘솔 + IaC 변수 변경 + 재배포) + 문서.

**Performance Goals**: N/A. 배치 롤링 중 수 분 다운(단일 태스크, 기존과 동일).

**Constraints**: dev 무변경(FR-006) · 소비자 미연결(FR-007) · 기존 공유 큐·DLQ 메시지 무접촉(FR-009) · 발행 잡(매시 :00)·벡터 잡(매시 :30) 시각을 피해 전환 · KB-548 보다 먼저.

**Scale/Scope**: 큐 2개 생성, tfvars 1줄, apply 1회, CI 수동 실행 1회, 저장소 파일 2개.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Test-First** — 앱 코드 변경이 없어 Kotest 대상이 없다(설정만 바꾸는 노출·연결 변경엔 테스트를 만들지 않는 기존 결정과 같은 결 — KB-380·KB-411). 대신 **검증 절차를 구현 전에 quickstart 로 확정**하고, 실패 조건(plan 에 예상 외 변경·태스크 env 가 옛 URL·`failed>0`·SENT 건수 ≠ 새 큐 메시지 수)을 명시해 그 절차로 Green 을 판정한다. 기존 앱 테스트는 무변경으로 그린 유지. **통과(정당화 기록)**.
- **II. Bounded Contexts** — 패키지·도메인 변경 없음. **통과**.
- **III. Layered Dependency Direction** — 모듈 의존 변경 없음. `common.port.mq` seam·`common.infra.mq` 어댑터 그대로(큐 URL 은 env 주입). **통과**.
- **IV. Persistence Ownership** — 스키마·엔티티 변경 없음. outbox 상태 전이 규칙도 그대로(발행 실패 = PENDING 유지). **통과**.
- **V. Language Policy** — 해당 없음. **통과**.
- **Additional Constraints** — 트랜잭션·외부 호출 배치 무관. **통과**.

Post-design 재확인: Phase 1 산출물은 콘솔 설정값·tfvars 1줄·apply/CI 절차·문서뿐이고 코드 무변경 — 게이트 유지.

## Project Structure

### Documentation (this feature)

```text
specs/kb-547-prod-content-queue/
├── plan.md              # 이 문서
├── research.md          # Phase 0 — 큐 속성·전환 메커니즘·전환 창·검증 방법 결정
├── data-model.md        # Phase 1 — 큐 자원 모델 + 전환 중 outbox 상태 전이
├── quickstart.md        # Phase 1 — 운영 절차 = 검증 절차
├── contracts/
│   └── prod-content-queue.md  # KB-549·KB-551 에 넘기는 prod 큐 계약(이름·속성·메시지 본문 무변경)
├── checklists/requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
iac/terraform/
├── prod.tfvars.example   # food_content_queue_name = "kbap-prod-generate-content-queue" 추가 (FR-008)
├── README.md             # SQS 재사용 서술에 "환경별 큐" 한 줄
└── prod.tfvars           # (gitignore — 메인 체크아웃 로컬) 큐 이름 교체, 커밋 대상 아님
```

`modules/ecs-environment/*.tf`·앱 코드·워크플로는 변경하지 않는다. `variables.tf` 의 기본값(공유 큐 이름)은 dev 가 쓰므로 그대로 둔다.

**Structure Decision**: 코드 무변경. 실작업은 AWS 콘솔(큐 생성)·메인 체크아웃 `iac/terraform`(tfvars·state·`.terraform` 이 거기 있음 — 워크트리엔 없음)에서의 apply·GitHub Actions 수동 실행이며, 저장소에는 예시 파일과 README 만 남긴다.

## Complexity Tracking

위반 없음.

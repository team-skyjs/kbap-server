# Implementation Plan: [FEATURE]

**Branch**: `[###-feature-name]` | **Date**: [DATE] | **Spec**: [link]

**Input**: Feature specification from `/specs/[###-feature-name]/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

세 가지를 한 브랜치에서: (1) terraform 장부를 **S3 백엔드(`kbap-terraform-state`, 잠금·버저닝) + workspace `dev`/`prod`** 로 옮겨 맥북·맥미니가 같은 장부를 보게 한다 — dev 는 맥미니 state 를 pull/push 로 이관, prod 는 AWS 실리소스 49개를 `import` 블록으로 복구(스크립트가 id 조회·생성, plan 이 "49 import + 3 add + 0 change" 일 때만 apply). (2) 그 apply 가 곧 prod Alloy 적용(코드에 이미 있음). (3) HTTPS 리스너에 **거부 규칙**(`*actuator*` 등 → 404) 을 추가한다 — CodeDeploy 가 리스너 기본 액션만 바꾸므로 허용 목록 forward 규칙은 카나리를 깨고, 거부 규칙은 전환과 독립. 퍼센트 인코딩 우회는 dev 실측으로 판정, 새면 WAF 승격.

## Technical Context

**Language/Version**: Terraform 1.15.8(맥북 `/opt/homebrew/bin/terraform`, 맥미니 동일) / AWS provider ~> 6.0 / bash 스크립트(aws cli v2 + jq)

**Primary Dependencies**: S3 백엔드(`use_lockfile`, 1.10+), `import` 블록(1.5+), CodeDeploy ECS blue/green(리스너 기본 액션만 전환), ALB 리스너 규칙 `path-pattern`(`*` 와일드카드, 값 5개 한도), KB-381 Alloy 코드(develop 머지됨)

**Storage**: S3 `kbap-terraform-state`(state 2개 + lock), 로컬 state 아카이브(맥미니)

**Testing**: 자동화 테스트 없음. 판정은 plan 숫자·`state list` 개수·curl·Grafana·카나리 1회. 스크립트는 dev state 와 id 전수 대조로 검증

**Target Platform**: 맥북(주도, `kbap-infra` 프로필 신규 등록) + 맥미니(dev 장부 내보내기, 이후 S3 재초기화)

**Project Type**: 인프라 운영(terraform 백엔드·import·리스너 규칙) + 스크립트 1개

**Performance Goals**: 해당 없음(요청 경로에 규칙 1개 추가 — ALB 규칙 평가 지연은 무시 수준)

**Constraints**: prod 리소스 재생성·교체 0(SC-002) · import 는 plan 게이트 통과 시만 · 카나리 무중단(FR-008) · 비밀(집 IP·SG id)은 README 에 값으로 쓰지 않음 · 앱 코드로 차단 금지(사용자 결정)

**Scale/Scope**: import 49 리소스, 신규 코드 = 백엔드 블록 + 리스너 규칙 + 변수 1 + 스크립트 1 + README

## Constitution Check

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First | ⚠️ 면제(사용자 지시, KB-380/381 동일) | 인프라 운영. 검증은 plan 게이트·curl·Grafana |
| II~V | ✅ 해당 없음 | 도메인·영속·언어 정책 무관, Kotlin 0줄 |
| 추가 제약 | ✅ | 빌드 무변경 |
| Kotlin 주석 금지 | ✅ | Kotlin 없음 |

**Post-design re-check**: 원칙 I 면제 외 위반 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-390-alb-allowlist-state-import/
├── plan.md · research.md(R-1~R-9) · data-model.md · quickstart.md
├── contracts/backend-and-alb-rule.md   # 백엔드 블록·버킷 생성 명령·거부 규칙 HCL·기대 응답표
├── contracts/import-ids.md             # prod import 49개 주소 ↔ id 출처
└── tasks.md
```

### Source Code (repository root)

```text
iac/terraform/versions.tf                       # backend "s3" 주석 해제(bucket·key·profile·use_lockfile·encrypt)
iac/terraform/.gitignore                        # + import.*.tf, *.tfvars.generated, _local-state-archive/
iac/scripts/gen-import-blocks.sh                # 신규: <env> [--check] — AWS 조회 → import.<env>.tf + <env>.tfvars.generated; --check 는 기존 state 와 id 대조
iac/terraform/modules/ecs-environment/alb.tf    # + aws_lb_listener_rule.block_paths (fixed-response 404)
iac/terraform/modules/ecs-environment/variables.tf · iac/terraform/variables.tf · main.tf   # + blocked_path_patterns
iac/terraform/{dev,prod}.tfvars.example         # + blocked_path_patterns 예시(prod 는 swagger·api-docs 포함)
iac/terraform/README.md                         # "처음 세우기"·"알아둘 것" 개정: S3 백엔드·workspace dev/prod·tfvars 복원표·import 절차·차단 규칙·WAF 승격 조건
```

**Structure Decision**: 코드 변경은 terraform 3파일 + 스크립트 1 + 문서. 새 모듈 없음. `import.*.tf` 는 일시 파일(gitignore).

## 설계 요점

1. **백엔드**: S3 + `use_lockfile` + workspace(R-1). 버킷은 terraform 밖 1회 생성. `profile = "kbap-infra"` 고정.
2. **dev 이관**: `state pull` → `state push`(R-2). `init -migrate-state` 는 EKS 유산까지 끌고 와 기각.
3. **prod 복구**: `gen-import-blocks.sh` → `import` 블록 → plan 게이트 "49 import / 3 add / 0 change / 0 destroy"(R-3·R-4). 태스크 정의는 최신 리비전 ARN(ignore_changes 로 diff 없음). replace 1개라도 있으면 중단.
4. **prod Alloy**: 3 의 apply 에 포함(R-7).
5. **차단 규칙**: 거부 규칙 priority 10, `path-pattern ["*actuator*"]`(prod +swagger·api-docs) → 404. 기본 액션(CodeDeploy 소유) 불변(R-6). `/%61ctuator` 실측 → 새면 WAF 승격 결정.
6. **자격증명**: 맥북 `kbap-infra` 프로필 필수(R-8).

## 검증 계획

| 단계 | 판정 |
|---|---|
| 백엔드 | `terraform init` 성공, S3 에 `env:/dev/…`·`env:/prod/…` 생성 |
| dev 이관 | 맥북·맥미니 `plan` 모두 "No changes", `state list` 개수 동일 |
| 스크립트 | `--check dev` 에서 49개 id 전수 일치 |
| prod import | plan "49 import, 3 add, 0 change, 0 destroy" → apply → 재plan "No changes", prod 리소스 생성시각 불변 |
| prod Alloy | DAEMON running = 인스턴스 수, Grafana `up{env="prod"}` = 2, 호스트 = N |
| 차단 규칙 | curl 4종 404(`%61` 은 결정 지점), 허용 3종 200, 타깃 healthy, 카나리 1회 정상 |

## Complexity Tracking

위반 없음.

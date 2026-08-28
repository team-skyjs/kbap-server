# Implementation Plan: [FEATURE]

**Branch**: `[###-feature-name]` | **Date**: [DATE] | **Spec**: [link]

**Input**: Feature specification from `/specs/[###-feature-name]/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

각 EC2 호스트에 Grafana Alloy 를 **ECS DAEMON 서비스(host 네트워크)** 로 하나씩 띄워, docker.sock 으로 그 호스트의 api·batch 컨테이너를 찾아 `/actuator/prometheus` 를 15초마다 읽고, `prometheus.exporter.unix` 로 호스트 자원까지 함께 홈서버 Prometheus 로 **remote_write** 한다. 전송 경로는 기존 Cloudflare Tunnel + Access 서비스 토큰(헤더), 라벨은 `env`(external)·`host`(hostname)·`instance`(태스크 단위)·`version`(태스크 정의 리비전) 을 relabel 로 붙여 카나리 blue/green 비교가 되게 한다. 앱·ALB·SG·api/batch 태스크 정의는 건드리지 않는다. Kotlin 변경 0 — terraform 파일 2개(`alloy.tf` + 설정 템플릿) + 변수 + README 가 전부.

## Technical Context

**Language/Version**: Terraform ≥ 1.7 / AWS provider ~> 6.0 (기존) · Grafana Alloy v1.x(설정 언어 Alloy) · 홈서버 Prometheus 3.x(`prom/prometheus:latest`)

**Primary Dependencies**: ECS(EC2 launch type, DAEMON)·docker.sock·Cloudflare Tunnel/Access(기존)·SSM SecureString(기존 `secrets.tf` 규칙). 앱 측 전제: KB-380(`/actuator/prometheus` 8080, `application` 태그)

**Storage**: 홈서버 Prometheus TSDB(보존 15일). AWS 측 영속 없음(Alloy WAL 은 컨테이너 임시 스토리지)

**Testing**: 자동화 테스트 없음(사용자 결정, KB-380 동일). quickstart 의 dev 시나리오 5개 + `alloy fmt` 문법 점검

**Target Platform**: ECS on EC2(ECS-optimized AL2023, docker), t3.medium 풀 2개(api·batch), dev·prod

**Project Type**: 인프라(terraform) + 관측 설정

**Performance Goals**: scrape 15s, 앱 응답 p95 영향 없음(SC-006). Alloy 예약 CPU 128/메모리 128(hard 384)

**Constraints**: SG 인바운드·앱 컨테이너 정의·ALB 변경 0(FR-004·007) · 아웃바운드만(FR-003) · 토큰은 SSM SecureString 만(FR-009) · 카나리 메모리 여유(api 1536×2 + Alloy 128 ≤ 3.6 GiB) · Alloy UI 포트는 localhost 바인드(host 네트워크)

**Scale/Scope**: 인스턴스 3~4대 × 환경 2 = Alloy 6~8개, 시계열 ≈ 태스크당 1.5k + 호스트당 0.5k

## Constitution Check

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First | ⚠️ 면제(사용자 지시) | 인프라·설정 작업, 단위 테스트 대상 코드 없음. 검증은 quickstart 시나리오. KB-380 과 동일 결정 |
| II. Bounded Contexts | ✅ 해당 없음 | 도메인 코드 접촉 없음 |
| III. Layered Dependency Direction | ✅ 해당 없음 | 모듈 의존 변경 없음(Kotlin 0줄) |
| IV. Persistence Ownership | ✅ 해당 없음 | |
| V. Domain Content Language | ✅ 해당 없음 | |
| 추가 제약(부트앱·빌드) | ✅ | 빌드 파일 무변경 |
| Kotlin 주석 금지 | ✅ | Kotlin 없음. terraform·Alloy 설정 주석은 규약 밖 |

**Post-design re-check**: 원칙 I 면제 외 위반 없음. Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-381-alloy-daemon-remote-write/
├── plan.md              # 이 파일
├── research.md          # R-1~R-9 (DAEMON+host net · docker discovery · relabel · Access 토큰 · unix exporter · templatefile 전달 · 리소스 · 홈서버 · 검증)
├── data-model.md        # 라벨 스키마·설정/시크릿 목록·상태 전이
├── quickstart.md        # 사용자 준비물(홈서버·Tunnel·Access·SSM) → dev apply → 시나리오 검증 → prod → 되돌리기
├── contracts/
│   └── alloy-config.md  # terraform 변수·ECS 리소스·컨테이너 command·Alloy 설정 템플릿 전문·홈서버 수신 계약·검증 PromQL
└── tasks.md             # /speckit-tasks 산출
```

### Source Code (repository root)

```text
iac/terraform/modules/ecs-environment/
├── alloy.tf                        # 신규: log group · task definition(host net, docker.sock+/proc·/sys·/ 바인드, ALLOY_CONFIG env, secrets 2) · DAEMON service
├── alloy.config.alloy.tftpl        # 신규: Alloy 설정 템플릿 (contracts/alloy-config.md §4 그대로)
├── variables.tf                    # + home_prometheus_remote_write_url · alloy_image · alloy_secret_names
├── secrets.tf                      # secret_names 에 alloy_secret_names 합류(실행 롤 정책은 ${ssm_prefix}/* 라 IAM 변경 없음)
└── outputs.tf                      # + alloy_service_name
iac/terraform/
├── variables.tf · main.tf          # 루트 변수 전달 2개
├── dev.tfvars.example · prod.tfvars.example   # remote_write_url · alloy_image 예시
└── README.md                       # Alloy 절: 구성·필요 SSM 키·라벨 규약·홈서버 전제·카나리 메모리 여유·토큰 403=유실 주의
```

**Structure Decision**: 기존 `ecs-environment` 모듈 안에 리소스 3개 + 템플릿 1개. 새 모듈·새 리소스 유형(EC2·LB) 없음(SC-007). Kotlin 소스 변경 0.

## 설계 요점 (상세는 research.md·contracts/)

1. **배치**: DAEMON + `network_mode=host` — 호스트명이 곧 `host` 라벨, bridge IP 직접 도달, unix exporter 정합(R-1)
2. **발견**: docker.sock 바인드 + `discovery.docker`(15s) → 컨테이너 이름 `api|batch` & 포트 8080 만 keep(R-2·R-3)
3. **라벨**: `instance=<env>-<container>-<task6>`, `version=<리비전>`, `external_labels{env,host}` — 앱 변경 0(R-3)
4. **전송**: remote_write + CF Access 헤더(SSM → env → `sys.env`), WAL 기본(R-4). **403 은 재시도 없이 유실**이라 토큰 오설정은 Alloy 로그로 즉시 확인
5. **호스트 메트릭**: `/proc`·`/sys`·`/` 읽기전용 바인드 + `prometheus.exporter.unix`(R-5)
6. **설정 전달**: `templatefile` → env `ALLOY_CONFIG` → `sh -c 'printf … > /etc/alloy/config.alloy && exec alloy run …'`. SSM 파라미터·커스텀 이미지 없음(R-6)
7. **리소스**: cpu 128 / mem 128(예약)·384(hard). 이미지 태그 고정(R-7)

## 검증 계획 (테스트 코드 없음)

| 단계 | 확인 | FR |
|---|---|---|
| 로컬 | `alloy fmt` 로 템플릿 문법, `terraform validate`(state 있는 머신) | — |
| 홈서버 | 토큰 없이 403 / 토큰으로 도달 | FR-006 |
| dev apply | plan 이 신규 3 리소스뿐(SG·앱 태스크정의 diff 0) | FR-004·007 |
| dev 시나리오 | `up` = 태스크 수, 호스트 메트릭 = 인스턴스 수, 카나리 후 `version` 2개, refresh 후 새 host 5분 내, 30분 단절 후 백필, 토큰 폐기 시 정지+앱 무영향 | FR-001~006·008 |
| 문서 | README Alloy 절 | FR-010 |

## Complexity Tracking

위반 없음 — 해당 없음.

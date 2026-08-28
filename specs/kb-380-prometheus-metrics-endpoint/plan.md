# Implementation Plan: 앱 내부 메트릭 노출 — api·batch Prometheus 엔드포인트 + 배치 헬스체크

**Branch**: `kb-380-prometheus-metrics-endpoint` | **Date**: 2026-08-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-380-prometheus-metrics-endpoint/spec.md`

## Summary

api·batch 두 부트앱에 Prometheus 메트릭 레지스트리를 얹어 JVM·HikariCP·HTTP·Spring Batch 잡 메트릭을 기존 서비스 포트(8080)의 `/actuator/prometheus` 로 내놓는다. 포트 분리·앱 측 접근 제어·TG 헬스경로 변경은 하지 않는다 — api 외 경로의 공개 차단은 ALB 리스너 규칙으로 후속 처리(범위 밖). batch 에는 처음으로 ECS 컨테이너 헬스체크를 단다. 코드 변경은 의존성 3줄 + yml 2개, 인프라는 `batch.tf` 헬스체크 하나. 테스트 코드는 작성하지 않는다(사용자 지시). 프로덕션 Kotlin 변경은 0(R-6 판정 시 최대 1파일).

## Technical Context

**Language/Version**: Kotlin 2.3.21 / JDK 21 / Spring Boot 4.1.0 (Spring Batch 6.0.4, Micrometer — Boot BOM)

**Primary Dependencies**: 신규 `io.micrometer:micrometer-registry-prometheus`(api·batch `runtimeOnly`), batch 에 `spring-boot-starter-actuator`. 기존: actuator(api), spring-boot-starter-batch, data-jpa(HikariCP)

**Storage**: 없음 — 영속 변경 0

**Testing**: 자동화 테스트 없음(사용자 지시). 로컬 `bootRun` + curl, dev 배포 확인(quickstart)

**Target Platform**: ECS on EC2(bridge, 동적 hostPort) — dev·prod. 런타임 이미지 `eclipse-temurin:21-jre`(curl 포함)

**Project Type**: web-service(멀티모듈 모놀리스) + terraform 소폭 변경(batch 헬스체크)

**Performance Goals**: 요청 경로에 추가 코드 없음 — 서비스 p95 영향 없음(SC-005)

**Constraints**: 서비스 포트·태스크 정의 포트·SG·ALB 규칙 변경 0(FR-004) · 관리 경로 8080·`/actuator` 유지, 앱 측 접근 제어 없음(FR-003) · 기존 ALB·ECS 헬스체크 무중단(FR-006) · CI 배포 후 batch 헬스체크 유지(FR-007)

**Scale/Scope**: 앱 2개, 환경 2개. 변경 파일 ≈ 7(카탈로그 1·빌드 2·yml 2·terraform 1·README 1)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First | ⚠️ 면제(사용자 지시) | 사용자가 테스트 작성을 불필요로 판단(2026-08-27). 변경이 프레임워크 기본 기능의 설정 노출뿐이라 앱 로직이 없다. 검증은 quickstart 의 로컬 curl + dev 배포 확인. 회귀 검출은 KB-381 이후 Grafana `up`/대시보드 공백에 의존 — 사용자 지시가 헌법보다 우선(CLAUDE.md) |
| II. Bounded Contexts | ✅ 해당 없음 | 도메인 패키지 접촉 없음 |
| III. Layered Dependency Direction | ✅ | 신규 의존은 부트앱에만. `:common` 무변경 |
| IV. Persistence Ownership | ✅ 해당 없음 | |
| V. Domain Content Language | ✅ 해당 없음 | |
| 추가 제약(부트앱 2개·buildSrc 컨벤션) | ✅ | 모듈 build 파일 문자열 표기, 좌표는 `libs.versions.toml` |
| Kotlin 주석 금지 | ✅ | 신규 Kotlin 없음(R-6 override 시에도 주석 없음) |

**Post-design re-check**: 원칙 I 면제 외 위반 없음 — Complexity Tracking 불필요(면제는 설계 복잡도가 아니라 검증 방식 결정).

## Project Structure

### Documentation (this feature)

```text
specs/kb-380-prometheus-metrics-endpoint/
├── plan.md              # 이 파일
├── research.md          # R-1~R-8 (레지스트리·8080 유지·공개 차단은 후속·batch 헬스체크·terraform replace·Batch 6 관측·테스트·태그)
├── data-model.md        # 비영속 런타임 모델 + 요구 메트릭군 ↔ 미터 이름
├── quickstart.md        # 로컬 검증 + dev/prod 롤아웃 순서(앱 먼저 → terraform → batch 재배포) + 되돌리기
├── contracts/
│   └── metrics-endpoint.md   # 엔드포인트 응답 표, 필수 메트릭 패밀리, terraform 측 계약
└── tasks.md             # /speckit-tasks 산출
```

### Source Code (repository root)

```text
gradle/libs.versions.toml                       # + micrometer-registry-prometheus
api/build.gradle.kts                            # + "runtimeOnly"(libs.micrometer.registry.prometheus)
api/src/main/resources/application.yml          # + management.endpoints.web.exposure.include=health,prometheus / management.metrics.tags.application
batch/build.gradle.kts                          # + actuator 스타터, + micrometer-registry-prometheus
batch/src/main/resources/application.yml        # + management.* (api 와 동일 2개)
batch/src/main/kotlin/com/kbap/batch/config/BatchJdbcJobRepositoryConfig.kt   # (조건부, R-6) getObservationRegistry() override — 테스트가 NOOP 판정 시만
iac/terraform/modules/ecs-environment/batch.tf  # 컨테이너 healthCheck 신설 (/actuator/health/readiness)
iac/terraform/README.md                         # batch 헬스체크 롤아웃 순서 + 공개 차단(ALB 규칙) 후속 메모
```

**Structure Decision**: Kotlin 변경 0(R-6 판정 시 최대 1파일). 신규 소스 파일 없음. 인프라는 `batch.tf` 한 곳 — 새 리소스 없음(SC-006).

## 설계 요점 (상세는 research.md·contracts/)

1. **노출**: `management.endpoints.web.exposure.include: health,prometheus`. 포트·base-path 무변경.
2. **공개 차단**: 이 기능에서 하지 않음. ALB 리스너 규칙 후속(허용 목록 방식 권장 — R-3 메모).
3. **태그**: `management.metrics.tags.application: ${spring.application.name}`.
4. **batch 헬스체크**: `batch.tf` 에 api 와 동일 파라미터로 신설. 롤아웃은 batch 이미지 먼저 → `-replace` → CI 재배포.
5. **Batch 잡 메트릭**: Boot 4.1 `BatchObservationAutoConfiguration` 주입 경로에 의존 — 로컬 bootRun 에서 잡 1회 트리거 후 `grep spring_batch_job_seconds` 로 판정, 부재면 `getObservationRegistry()` override.

## 검증 계획 (테스트 코드 없음 — 사용자 지시)

| 단계 | 확인 | 판정 FR |
|---|---|---|
| 로컬 `:api:bootRun` | `curl localhost:8080/actuator/prometheus` 에 `jvm_memory_used_bytes`·`hikaricp_connections_active`·`http_server_requests_seconds_count`·`application="kbap-api"`; `/actuator/env` 404; `/actuator/health/readiness` 200 | FR-001·002·003·006 |
| 로컬 `:batch:bootRun` | 잡 1회 트리거 후 `grep spring_batch_job_seconds` (부재 → R-6 override 후 재확인); `application="kbap-batch"` | FR-001(batch)·002 |
| dev 배포 | 컨테이너 안 curl 동일, ECS `healthStatus=HEALTHY`, SG/ALB/api 태스크정의 diff 0, CI 재배포 후 리비전에 `healthCheck` | FR-004·005·007·008 |

## Complexity Tracking

위반 없음 — 해당 없음.

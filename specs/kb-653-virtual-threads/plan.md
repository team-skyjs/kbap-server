# Implementation Plan: 톰캣 스레드 풀을 버추얼 스레드로 전환

**Branch**: `kb-653-virtual-threads` | **Date**: 2026-09-30 | **Spec**: [spec.md](spec.md) | **Jira**: [KB-653](https://simhani1.atlassian.net/browse/KB-653)

**Input**: Feature specification from `/specs/kb-653-virtual-threads/spec.md`

## Summary

dev 프로필에만 `spring.threads.virtual.enabled: true` 를 켜 Tomcat 요청 처리를 버추얼 스레드로 바꾼다. DB 커넥션 풀은 사용자 결정으로 손대지 않는다 — RDS `max_connections` 가 60 이라 확대 여지가 작고, 이번 측정에서 병목이 DB 로 옮겨가는 것을 확인하는 것이 목적이다(research D1 의 예상 관측). 관측은 새 지표 없이 이미 나가는 `http_server_requests_active` 를 그라파나 앱 대시보드에 패널로 추가하고, Tomcat 패널에 "플랫폼 스레드 모드만 유효" 를 표시한다. 핀닝은 기존 JFR 프로파일의 `jdk.VirtualThreadPinned` 이벤트로 dev 캠페인에서 확인한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 — 코드 변경 없음(설정·대시보드 JSON·문서)

**Primary Dependencies**: Spring Boot 4.1 `spring.threads.virtual.enabled`(Tomcat executor 교체), Micrometer HTTP 관측 LongTaskTimer, 기존 JFR 프로파일

**Storage**: 변경 없음. RDS 60 연결 한도는 제약 조건으로 기록

**Testing**: 기존 스위트 그린. 신규 테스트 없음(research D4). 로컬 dev 프로필 실노출 + dev 배포 후 사용자 재측정

**Target Platform**: dev ECS api 2대. develop 푸시로 자동 배포

**Project Type**: 설정 1줄 + 대시보드 JSON + 문서 1개

**Performance Goals**: SC-001(처리 중 요청 수 > 200)·SC-002(p95 < 4.5s) 를 사용자 재측정으로 판정. SC-003(커넥션 대기 초과 0건)은 풀 미변경 결정으로 **관측 대상**으로 격하(research D1)

**Constraints**: prod·staging·local·batch 무변경 · JVM 플래그 불가(Terraform 소유) · 새 지표 금지 · Kotlin 코드 무변경

**Scale/Scope**: 수정 파일 3개 — `api/src/main/resources/application-dev.yml`, `docs/observability/grafana-app-dashboard.json`, `docs/observability/grafana-app-dashboard.md`

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS | 프레임워크 토글·대시보드 정의라 검증 가능한 도메인 동작이 없다. 기존 스위트가 회귀 방어, 실노출은 로컬 bootRun(스레드 이름)과 dev 재측정으로 확인 — KB-380·411·652 선례. |
| II. Bounded Contexts | PASS | 도메인 코드 무변경. |
| III. Layered Dependency Direction | PASS | 의존 변경 없음. |
| IV. Persistence Ownership | PASS | 영속 코드·풀 설정 무변경. |
| V. Language Policy | PASS | 무관. |

**게이트 판정**: PASS. Complexity Tracking 기록 사항 없음.

**Phase 1 재검토**: 변화 없음. PASS 유지.

## Project Structure

### Documentation (this feature)

```text
specs/kb-653-virtual-threads/
├── spec.md · plan.md · research.md(D1~D5) · data-model.md · quickstart.md · tasks.md
```

`contracts/` 없음 — 외부 인터페이스 변경 없음.

### Source Code (repository root)

```text
api/src/main/resources/
└── application-dev.yml                          # + spring.threads.virtual.enabled: true (주석으로 KB-653·RDS 60 제약 명시)
docs/observability/
├── grafana-app-dashboard.json                   # 패널 8 "처리 중 HTTP 요청 수" 추가(y=16 w=24), GC y=24, Tomcat 패널 제목·description 갱신
└── grafana-app-dashboard.md                     # 패널 표에 행 추가, Tomcat 행 주석, 버추얼 스레드 모드 안내 한 단락
```

**Structure Decision**: Kotlin 소스는 만지지 않는다. 설정은 dev 프로필 파일 하나, 관측은 문서 폴더의 대시보드 정의만.

## 구현 순서 (tasks 기준)

1. 로컬 기준선: 전환 전 dev 프로필 bootRun 으로 요청 스레드 이름(`http-nio-8080-exec-*`)과 `/actuator/prometheus` 의 active 지표 이름을 확정한다(대시보드 질의에 쓸 정확한 이름).
2. `application-dev.yml` 에 설정 추가 → 같은 bootRun 으로 스레드 이름이 `tomcat-handler-*` 로 바뀜을 확인.
3. 대시보드 JSON·문서 갱신(1 에서 확정한 지표 이름 사용).
4. `./gradlew :api:test` 그린 → 커밋 → draft PR.
5. 머지 후(사용자): AWS 안에서 k6 재측정·JFR 핀닝 확인 → Jira DoD 기록.

## 범위 밖 보고

- RDS 60 연결 한도(dev·prod 동일, db.t4g.micro)가 다음 벽이다. 풀 확대는 인스턴스당 ~20 이 상한이며, 그 이상은 `db.t4g.small` 증설(월 약 $12)이 필요하다. 이번 재측정의 Hikari pending·500 건수가 그 결정의 근거가 된다.

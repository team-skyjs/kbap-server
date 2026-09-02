# Implementation Plan: 스프링 컨테이너 메트릭 개선 (Tomcat 스레드풀·HTTP 지연 백분위 노출)

**Branch**: `kb-411-spring-container-metrics` | **Date**: 2026-09-02 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-411-spring-container-metrics/spec.md`

## Summary

api 앱의 관측 노출에서 비어 있는 두 지표군 — Tomcat 스레드풀(`tomcat_threads_*`)과 HTTP 지연 히스토그램 버킷(`http_server_requests_seconds_bucket`) — 을 **설정 두 줄**로 켠다. 코드 변경은 `api/src/main/resources/application.yml` 뿐이며, 검증은 기존 `@IntegrationTest` 컨텍스트 안의 테스트 하나로 한다(새 컨텍스트 금지 — KB-392). 그 위에 홈서버 Grafana 앱 대시보드(env 필터 + 6 패널)를 PromQL 로 직접 구성하고, 정의 JSON 과 패널별 질의 설명을 `docs/observability/` 에 남긴다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21, Spring Boot 4.1.0 (actuator + `micrometer-registry-prometheus`, 이미 의존)

**Primary Dependencies**: Spring Boot 4.1.0 모듈 `spring-boot-tomcat`(`TomcatMetricsBinder`, `server.tomcat.mbeanregistry.enabled`)·`spring-boot-micrometer-metrics`(`PropertiesMeterFilter`, `management.metrics.distribution.*`). jar 메타데이터에서 두 프로퍼티 존재 확인(2026-09-02).

**Storage**: N/A (앱 변경 없음). 시계열 저장은 홈서버 Prometheus(변경 없음).

**Testing**: Kotest BehaviorSpec + `@IntegrationTest`(MockMvc, 기존 단일 컨텍스트). MockMvc 는 필터 체인을 포함하므로 `ServerHttpObservationFilter` 가 `http.server.requests` 를 기록하고 `/actuator/prometheus` 로 읽을 수 있다. Tomcat 바인더는 실제 WebServer 가 있어야 동작하므로 MOCK 환경에서 `tomcat_*` 는 나오지 않는다 → 설정 바인딩(`TomcatServerProperties.mbeanregistry.enabled`)만 검증.

**Target Platform**: ECS(EC2 bridge) 컨테이너 2대/env, Alloy 가 `/actuator/prometheus` 를 15초 간격 scrape → 홈 Prometheus remote_write.

**Project Type**: web-service 설정 변경 + 운영 대시보드(저장소 밖 Grafana) + 문서.

**Performance Goals**: 노출 지표 수 증가가 scrape 응답 크기·홈 저장소 시계열 수에 미치는 영향이 예측 범위 안(연구 §3).

**Constraints**: 백분위는 앱 내 계산(`percentiles`) 금지 — 인스턴스 2대 합산 불가. 히스토그램 버킷 범위를 5ms~10s 로 고정해 카디널리티 제한. 배치 앱 설정 불변. 관측 엔드포인트 노출·경로·접근 방식 불변(KB-380).

**Scale/Scope**: 설정 2줄 + 테스트 1 클래스 + 문서 2 파일 + Grafana 대시보드 1 개.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | 예외(정당화) | 자동 테스트 없음 — 사용자 결정(2026-09-02). Complexity Tracking 참조. 검증은 로컬 bootRun 실노출(버킷 51/조합·Tomcat 3종 확인 완료)과 dev 카디널리티 확인. |
| II. Bounded Contexts | N/A | 도메인 코드 변경 없음. |
| III. Layered Dependency | N/A | 모듈·패키지 의존 변경 없음. 테스트는 `com.kbap.api.core.metrics` 에 둔다(api 전용 공통재 위치). |
| IV. Persistence Ownership | N/A | 영속 변경 없음. |
| V. Language Policy | N/A | 콘텐츠 무관. |
| Additional Constraints | PASS | 스택 변경 없음. 배치 bootJar 불변. |

**Post-design re-check (Phase 1 후)**: 위 판정 유지. 새 컨텍스트·새 의존·새 모듈 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-411-spring-container-metrics/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 프로퍼티 확정·MOCK 환경 제약·카디널리티 추정·대시보드 PromQL
├── data-model.md        # Phase 1 — 노출 지표 패밀리·라벨 정의
├── quickstart.md        # Phase 1 — 로컬 확인·dev 검증·대시보드 import 절차
├── contracts/
│   └── prometheus-exposition.md   # 관측 엔드포인트가 새로 보장하는 지표 계약
└── tasks.md             # /speckit-tasks 가 생성
```

### Source Code (repository root)

```text
api/src/main/resources/application.yml                    # server.tomcat.mbeanregistry + management.metrics.distribution 추가 (테스트 없음 — 사용자 결정)

docs/observability/
├── grafana-app-dashboard.json                              # Grafana export (US4)
└── grafana-app-dashboard.md                                # env 변수·패널별 PromQL 의미 한 줄씩 (US4)
```

**Structure Decision**: 기존 레이아웃 그대로. 테스트는 api 공통재 위치(`com.kbap.api.core`) 아래 `metrics` 하위 패키지 하나. `docs/observability/` 는 신설 — 기존 `docs/performance/`(k6 런북) 와 같은 급의 운영 문서 디렉터리.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 원칙 I Test-First 미적용(자동 테스트 없음) | 사용자 결정(2026-09-02). 변경이 프레임워크 기본 기능의 설정 노출 2블록뿐이라 KB-380 의 "설정 노출은 자동화 테스트 없음" 결정과 같은 선상. Tomcat 지표는 MOCK 컨텍스트에서 검증 불가해 테스트 가치가 반쪽. | 테스트를 두려면 `RANDOM_PORT` 새 컨텍스트가 필요(KB-392 위반) 또는 설정 바인딩만 검사하는 저가치 테스트. 대신 로컬 bootRun 실노출 확인(51 버킷/조합·Tomcat 3종)과 dev 카디널리티 확인으로 대체. |

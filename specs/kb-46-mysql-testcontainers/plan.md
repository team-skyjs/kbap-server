# Implementation Plan: MySQL Testcontainers 도입 (프로덕션-동등 통합 테스트)

**Branch**: `kb-46-mysql-testcontainers` | **Date**: 2026-07-05 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-46-mysql-testcontainers/spec.md`

## Summary

DB-backed 통합 테스트를 임베디드 H2 → **운영과 동일한 MySQL 8.4(Testcontainers)** 로 교체한다. 핵심 가치(P1)는 **운영 Flyway 마이그레이션 전체를 테스트에서 실제로 적용·검증**하는 것 — 현재 테스트는 `flyway.enabled=false`+`ddl-auto=create-drop` 이라 `JSON_OBJECTAGG`·`MODIFY COLUMN` 같은 MySQL 전용 마이그레이션 SQL 이 한 줄도 실행되지 않는 사각지대다. 부차적(P2)으로 영속 어댑터 테스트를 실 엔진에서 돌려 향후 MySQL 방언 의존 코드가 자동 검증되게 한다. 컨테이너는 Spring `@ServiceConnection`+컨텍스트 캐싱으로 모듈 테스트당 1회만 뜨게 해(P3) 속도 트레이드오프를 상각한다. **운영 런타임 코드·도메인·엔티티·마이그레이션 SQL 은 변경하지 않는다**(테스트 실행 환경만 교체).

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1(`spring-boot-testcontainers`), Testcontainers(`mysql` 모듈, 버전은 Boot BOM 관리), Flyway(+flyway-mysql), Kotest 5.9 + `kotest-extensions-spring`, Hibernate/JPA

**Storage**: MySQL 8.4 — 운영 + (본 작업 이후) 통합 테스트. MongoDB 는 이번 범위 밖(무변경)

**Testing**: Kotest `BehaviorSpec` + `SpringExtension`, `@SpringBootTest` + `@ServiceConnection MySQLContainer`. 순수 도메인 단위 테스트는 컨테이너 미사용(무변경)

**Target Platform**: JVM 서버(Linux). DB-backed 테스트는 Docker 호환 컨테이너 런타임 필요

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스(백엔드 web service) — 테스트 인프라 변경

**Performance Goals**: 컨테이너는 **동일 Spring 테스트 컨텍스트당 1회** 기동(컨텍스트 캐싱으로 클래스 간 재사용). 한 모듈에 서로 다른 컨텍스트가 여러 개면 그만큼 기동될 수 있음 — 모듈당 단일 컨테이너(static 싱글턴)는 별도 최적화. 클래스마다 재기동 안 됨을 기동 횟수로 검증

**Constraints**: Docker 필요(미가용 시 명확 실패, FR-007). 전체 마이그레이션 체인이 MySQL 8.4 에서 클린 적용되어야 함. 순환 의존 금지(persistence↛api)

**Scale/Scope**: 전환 대상 DB-backed 테스트 ~13종 — `app:api`(웹/컨트롤러 다수) + `infra:persistence`(어댑터 3) + `app:batch`(부팅 1). 순수 도메인 단위 테스트 다수는 무영향

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First | ✅ PASS | 마이그레이션-검증 테스트를 **먼저 작성**해 컨테이너/Flyway 없이는 실패(Red)함을 확인한 뒤, 컨테이너 도입으로 Green. 기존 테스트 전환은 엔진 교체 후에도 Green 유지로 회귀 방지. |
| II. Bounded Contexts | ✅ PASS | 도메인 코드·컨텍스트 결합 변경 없음. 테스트 인프라·빌드만 손댐. |
| III. Layered Dependency | ✅ PASS | 공유 설정을 `infra:persistence` `testFixtures` 에 두고 `app:api` 가 `testImplementation(testFixtures(...))` 로 소비 — persistence↛api 라 **순환 없음**. 운영 의존 방향 불변. |
| IV. Persistence Encapsulation | ✅ PASS | 컨테이너 설정은 **test 전용**. `app:api` main 클래스패스에 JPA/영속 타입 노출 없음. ArchUnit `ModuleBoundaryTest`(main 스캔) 무영향. |
| V. Language Policy | ➖ N/A | 언어/콘텐츠 정책과 무관. |

**Additional Constraints note**: 헌법 기술 문구 "영속: MySQL(**+H2 test**)"(constitution.md L118)는 H2 제거(FR-008/D-5) 후 실체와 어긋난다 → **구현 완료 후 헌법 PATCH** 로 문구 갱신(후속 과제). 이는 원칙 위반이 아니라 문서 동기화이므로 **게이트 통과**.

**Gate 결과: PASS** — 정당화가 필요한 원칙 위반 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-46-mysql-testcontainers/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 설계 결정(D-1..D-7)
├── data-model.md        # Phase 1 — (신규 데이터 모델 없음: 근거 명시)
├── quickstart.md        # Phase 1 — 실행법·새 컨테이너 테스트 작성법
├── contracts/           # (외부 인터페이스 없음: 근거 명시)
└── tasks.md             # /speckit-tasks 산출 (이 명령 아님)
```

### Source Code (repository root)

```text
gradle/
└── libs.versions.toml                 # [수정] testcontainers·spring-boot-testcontainers 라이브러리 추가

infra/persistence/
├── build.gradle.kts                   # [수정] java-test-fixtures 적용, testFixtures 의존, h2 제거
└── src/
    ├── testFixtures/kotlin/com/meogo/infra/persistence/testsupport/
    │   ├── MySqlContainerConfig.kt     # [신규] @TestConfiguration @Bean @ServiceConnection MySQLContainer("mysql:8.4")
    │   └── MySqlIntegrationSpec.kt     # [신규] 공통 Kotest 베이스(BehaviorSpec+SpringExtension+@Import)
    └── test/kotlin/.../persistence/**  # [수정] 3개 어댑터 테스트를 컨테이너 베이스로 전환

app/api/
├── build.gradle.kts                   # [수정] testImplementation(testFixtures(:infra:persistence)), h2 제거
└── src/test/
    ├── resources/application.yml       # [수정] flyway.enabled=true, ddl-auto=validate (H2/create-drop 제거)
    └── kotlin/.../app/api/**           # [수정] @SpringBootTest 들에 컨테이너 설정 적용, 시드 픽스처 정합(D-7)
                                        # [신규] MigrationValidationTest — 마이그레이션 전체 적용 검증(P1 크라운)

app/batch/
└── src/test/...                        # [검토] 컨텍스트 로드 테스트가 DB 필요 시 동일 베이스 적용(현재 :common 만 의존 → 영향 최소)
```

**Structure Decision**: 멀티모듈 구조 유지. 공유 컨테이너 설정은 신규 모듈 없이 `infra:persistence`의 `testFixtures` 소스셋에 단일화하고, `app:api` 가 이를 소비한다(D-4). 마이그레이션 검증(P1)은 스키마 owner 인 `app:api` 테스트에 둔다(D-2). 운영 소스(`src/main`)는 전 모듈 무변경.

## Complexity Tracking

> Constitution Check 게이트 통과 — 정당화가 필요한 위반 없음. 해당 없음.

## Phase 0 산출

[research.md](./research.md) — 결정 D-1(통합 방식: `@ServiceConnection`+컨텍스트 캐싱)·D-2(Flyway ON@api, `ddl-auto=validate`)·D-3(persistence 는 실 엔진+Hibernate 스키마)·D-4(testFixtures 공유)·D-5(H2 제거)·D-6(Docker 미가용 명확 실패)·D-7(시드 정합 리스크).

## Phase 1 산출

- [data-model.md](./data-model.md) — 신규 엔티티 없음(테스트 실행 환경 교체). 근거 명시.
- [quickstart.md](./quickstart.md) — DB-backed 테스트 실행 전제(Docker)·새 컨테이너 테스트 작성법.
- contracts/ — 외부로 노출하는 API/스키마 변경 없음(내부 테스트 인프라)이라 생성하지 않음.
- Agent context: `CLAUDE.md` 의 `<!-- SPECKIT START/END -->` 참조를 본 plan 으로 갱신.

## 핵심 리스크 (구현 시 주목)

1. **마이그레이션 첫 실전 실행** — 전체 체인(ingredient 생성→시드→drop→jsonify→replace)이 MySQL 8.4 에서 처음 돌며 잠복 결함이 드러날 수 있다(목적이자 리스크). 로컬 docker 로 사전 검증 권장.
2. **`ddl-auto=validate` 드리프트** — 엔티티↔마이그레이션 스키마 불일치가 잡히면 수정 과제 발생. 과도하면 `none` 임시 완화 후 별도 이슈화.
3. **시드 픽스처 충돌(D-7)** — Flyway 시드와 수기 테스트 시드 중복 시 무결성 오류. 전환 필수 작업.
4. **컨테이너 재사용/속도** — 컨텍스트 캐싱이 깨지는 설정 분기(프로퍼티 상이)를 최소화해 컨테이너 다중 기동 회피.

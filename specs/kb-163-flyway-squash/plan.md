# Implementation Plan: Flyway 마이그레이션 스쿼시 — 스키마·시드 분리 및 프로필별 적용

**Branch**: `kb-163-flyway-squash` | **Date**: 2026-07-16 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-163-flyway-squash/spec.md`

## Summary

기존 Flyway 마이그레이션 22개(스키마 변경 + 데모 시드가 파일 단위로 얽힘)를 최종 상태 기준으로 재편한다: **스키마 전용 init 1개 + 마스터 시드(기피물질 카탈로그 81종)** 는 `db/migration`(전 환경), **데모 시드(음식 10건·매핑)** 는 `db/seed`(local·dev 만)로 분리하고 `spring.flyway.locations` 를 프로필별로 나눈다. 최종 스키마·데모 데이터는 docker MySQL 에 구 22개를 적용한 덤프에서 도출하고(diff 검증), 기존 데이터가 있는 홈서버 dev DB 는 **drop 이 아니라 flyway_schema_history 재기준선**으로 데이터 손실 0 전환한다(FR-005 — Jira 원안의 "drop 후 재생성"을 데이터 보존 요구에 따라 대체). 신규 코드 0줄 — 산출물은 SQL 리소스 3개, yml 3곳, 테스트 2개(신규 가드 1 + 경로 갱신 1), 삭제 22개.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (변경 없음 — 이번 작업의 실질 대상은 SQL·YAML 리소스)

**Primary Dependencies**: Spring Boot 4.1, Flyway(+flyway-mysql) — 신규 의존성 0

**Storage**: MySQL (prod/dev/local), 통합 테스트는 MySQL Testcontainers(`:core` testFixtures `MySqlContainerConfig`)

**Testing**: Kotest BehaviorSpec + JUnit 플랫폼. 신규 `MigrationLayoutTest`(리소스 가드) + 기존 `AvoidanceCatalogSeedSyncTest` 경로 갱신 + 전체 스위트(마이그레이션 적용 + `ddl-auto=validate`)

**Target Platform**: `:app:api`(스키마 owner, Flyway 실행 주체). `:app:batch` 는 flyway off — 영향 없음

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 — 이번 변경은 `:app:api` 리소스·테스트에 국한

**Performance Goals**: 해당 없음(발급 로직·API 무변경). 부수 효과: 빈 DB 초기화가 22단계 → 2~3단계

**Constraints**: KB-44 timestamp 버전 규칙 유지 · `out-of-order=true` 유지 · 시드-동기화 테스트의 리소스 경로 결합(CLAUDE.md 명시 주의사항) · 홈서버 DB 데이터 손실 0(FR-005) · prod 는 아직 미프로비저닝(이력 재작성 가능한 마지막 시점)

**Scale/Scope**: 마이그레이션 22개 삭제 → 신규 SQL 3개(init·마스터·데모), yml 3곳(base·local·dev), 테스트 2개. 도메인·애플리케이션 코드 무변경

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | ✅ | Red 진입점 = 신규 `MigrationLayoutTest`(db/migration 파일 구성·init 무INSERT·마스터 81건·데모 격리 가드) — 파일 부재 상태에서 Red 확인 후 SQL 작성으로 Green. `AvoidanceCatalogSeedSyncTest` 는 새 경로로 갱신해 enum↔시드 정합 검증 지속. 스키마 정합은 기존 통합 스위트(Testcontainers + validate)가 커버 (research R6) |
| II. Bounded Contexts | ✅ | 도메인 코드 무변경. 크로스 도메인 참조 구조 그대로(FK 는 Flyway 스키마가 강제 — 원칙 IV 각주와 일치) |
| III. Layered Dependency | ✅ | 모듈 의존 그래프 무변경, 신규 의존성 0 |
| IV. Persistence Encapsulation | ✅ | 엔티티·리포지토리 무변경. "스키마 owner = Flyway" 원칙을 유지한 채 파일 재편만 수행 |
| V. Domain Content Language | ✅ | 기피물질 카탈로그(ko + 9개 언어)를 DB 단일 출처 마스터 시드로 유지, 시드 정합 테스트 지속 — 원칙이 요구하는 "시드 정합으로 드리프트 차단" 그대로 |

**Post-Phase 1 재점검**: 위반 없음 — Complexity Tracking 해당 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-163-flyway-squash/
├── spec.md
├── plan.md              # 이 파일
├── research.md          # R1~R6 결정(덤프 도출·파일 배치·locations·재기준선·TDD 전략)
├── data-model.md        # 최종 테이블 7종 + 시드 데이터셋 개요
├── quickstart.md        # 검증 절차 + 홈서버 전환 런북
└── tasks.md             # /speckit-tasks 산출물(이 커맨드가 만들지 않음)
```

(contracts/ 없음 — API 표면·DTO 무변경이라 계약 산출물이 존재하지 않는다.)

### Source Code (repository root)

```text
app/api/src/main/resources/
├── application.yml                  # spring.flyway.locations: classpath:db/migration 명시(안전 기본값)
├── application-local.yml            # locations += classpath:db/seed
├── application-dev.yml              # locations += classpath:db/seed
└── db/
    ├── migration/
    │   ├── V<ts>__init_schema.sql            # 신규: 테이블 7종 스키마 전용
    │   ├── V<ts>__seed_avoidance_catalog.sql # 신규: 마스터 81종
    │   └── (기존 V2026.06.29~V2026.07.15 22개 삭제)
    └── seed/
        └── V<ts>__seed_demo_food_data.sql    # 신규: 데모 음식 10건 + 매핑

app/api/src/test/
├── resources/application.yml        # 무변경(베이스 locations 상속 → 데모 미포함, 테스트는 자체 시드)
└── kotlin/com/kbap/app/api/
    ├── migration/MigrationLayoutTest.kt      # 신규 가드 테스트 (Red 진입점)
    └── avoidance/AvoidanceCatalogSeedSyncTest.kt  # seedResourcePath 갱신
```

**Structure Decision**: 변경은 `:app:api` 리소스(스키마 owner)와 그 테스트에 국한된다. 도메인 모듈·`:application`·`:app:batch`(flyway off)·`:infra:*` 는 건드리지 않는다.

## 구현 축 (tasks 생성 지침)

1. **[Red] 가드 테스트**: `MigrationLayoutTest` 작성 — db/migration 파일 정확히 2개(init·마스터), init 에 `INSERT` 부재, 마스터 시드 기피물질 81행, `db/seed` 데모 존재, db/migration 전체에 `INSERT INTO food` 부재. 실행해 Red 확인.
2. **[도출] 덤프**: quickstart §1 로 docker MySQL 에 구 22개 적용 → 스키마 덤프(`--no-data`)·데모 데이터 덤프 확보.
3. **[Green] SQL 3개 작성 + 22개 삭제**: 덤프를 정리해 init·마스터·데모 파일 생성(버전 timestamp, init < master < demo), 구 22개 삭제. `AvoidanceCatalogSeedSyncTest` 경로 갱신. diff 검증(구 22개 결과 = 새 init 결과).
4. **[Green] locations 분리**: base yml 명시 + local·dev 오버라이드. 테스트 yml 무변경 — 데모 시드 전제 테스트가 깨지면 자체 시드로 수정.
5. **[검증] 전체 테스트** + quickstart §3 신규 DB 부팅 확인.
6. **[문서/운영] 홈서버 전환 런북**(quickstart §4)은 머지 후 운영 작업 — 코드 범위 밖이지만 PR 본문에 링크.

## Complexity Tracking

> 위반 없음 — 해당 없음.

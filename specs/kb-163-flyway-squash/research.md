# Research: Flyway 마이그레이션 스쿼시 (KB-163)

## R1. 스키마 전용 init 파일 도출 방법

- **Decision**: 로컬 docker MySQL 8 컨테이너에 기존 마이그레이션 22개를 버전 순으로 적용한 뒤 `mysqldump --no-data` 덤프를 정리해 `init_schema` 파일을 만든다. 검증은 "빈 DB + 새 init 적용" vs "빈 DB + 구 22개 적용" 두 덤프의 diff = 0 으로 한다(`flyway_schema_history` 제외, AUTO_INCREMENT 값 정규화).
- **Rationale**: 22개를 손으로 합성하면 컬럼 변형(rename·jsonify·enum 전환)이 누적된 최종 상태를 놓치기 쉽다. 실제 DB 가 만든 최종 상태를 덤프하면 정의상 정확하고, diff 로 기계 검증된다.
- **Alternatives considered**: 손 합성(오류 위험, 검증 수단 별도 필요 — 기각), Testcontainers 기반 자동 덤프 스크립트(1회성 작업에 과함 — 기각).

## R2. 데모 시드의 최종 상태 도출

- **Decision**: 같은 컨테이너에서 `food`·`food_avoidance_substance` 데이터를 `mysqldump --no-create-info` 로 덤프해 `db/seed` 시드 파일로 정리한다.
- **Rationale**: 초기 시드(음식 10건)는 이후 마이그레이션들(설명 단일화·spiciness·번역 jsonify·match key·content_status·enum 전환)로 변형됐다. 최종 행을 덤프하는 것이 유일하게 안전한 재작성법이다.
- **Alternatives considered**: 기존 시드 SQL 3개를 손으로 병합(변형 재현 누락 위험 — 기각).

## R3. 파일 배치와 버전

- **Decision**:
  - `db/migration/Vyyyy.MM.dd.HH.mm.ss__init_schema.sql` — 스키마 전용(테이블 7종, INSERT 0건)
  - `db/migration/Vyyyy.MM.dd.HH.mm.ss__seed_avoidance_catalog.sql` — 마스터(기피물질 81종)
  - `db/seed/Vyyyy.MM.dd.HH.mm.ss__seed_demo_food_data.sql` — 데모(음식 10건 + 음식-기피물질 매핑)
  - 버전은 KB-44 규칙(생성 시각 timestamp), init < master < demo 순으로 초 단위를 어긋나게 부여.
  - 기존 22개 파일 삭제.
- **Rationale**: Flyway 는 locations 가 달라도 버전 이력은 하나다 — 위치는 "환경별 포함 여부"만 가르고, 순서는 버전이 가른다. 마스터를 별도 파일로 두는 것은 시드-동기화 테스트(enum↔시드 정합)가 카탈로그 시드만 읽는 구조를 유지하기 위함이다.
- **Alternatives considered**: init 에 마스터 시드 포함(단일 파일이지만 시드-동기화 테스트가 스키마 DDL 을 함께 파싱해야 해 취약 — 기각), Flyway `afterMigrate` 콜백으로 데모 주입(이력에 안 남아 멱등 관리 수동 — 기각).

## R4. 프로필별 적용 범위 (spring.flyway.locations)

- **Decision**: 베이스 `application.yml` 에 `spring.flyway.locations: classpath:db/migration` 을 명시(prod·staging 의 안전 기본값). `application-local.yml`·`application-dev.yml` 에만 `classpath:db/migration,classpath:db/seed` 오버라이드. 테스트(`app/api/src/test/resources/application.yml`)는 **베이스 그대로(db/migration만)** — 통합 테스트는 전부 자체 시드 헬퍼(`FoodTestSeed`·`HomeTestSeed`)로 데이터를 만들므로 데모 시드 불요.
- **Rationale**: 기본값이 안전한 쪽(데모 미포함)이어야 새 프로필 추가 시 실수로 데모가 유입되지 않는다. 테스트가 데모 시드에 암묵 의존하지 않게 되면 테스트 격리도 좋아진다.
- **Risk**: 기존 테스트 중 데모 시드 행 수를 암묵 전제한 것이 있으면 깨진다 → 그 테스트를 자체 시드로 고친다(구현 중 발견 시).
- **Alternatives considered**: 테스트에 데모 포함(현행 유지) — 테스트가 데모 데이터에 결합되는 것을 지속시켜 기각.

## R5. 기존 DB(홈서버 dev) 전환 — 데이터 보존

- **Decision**: **재기준선(re-baseline)**. 절차: (1) 백업 덤프, (2) 실제 스키마가 새 init 결과와 동일한지 diff 확인, (3) `DROP TABLE flyway_schema_history`, (4) 1회 부팅 시 `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` + `SPRING_FLYWAY_BASELINE_VERSION=<데모 시드 버전>` 지정 → Flyway 가 비어있지 않은 스키마에 baseline 행만 기록하고 세 파일 모두 스킵, (5) 행 수 대조로 데이터 보존 확인. 이후 부팅부터 플래그 제거(신규 마이그레이션만 적용됨).
- **Rationale**: 사용자 요구(회원·음식 데이터 유실 금지). 스키마는 init 도출 원본이므로 동일 — 이력 장부만 바꾸면 데이터 무접촉. baseline-version 을 새 파일 중 최고 버전(데모)으로 잡아야 dev 의 locations(db/seed 포함)에서 데모 시드가 재적용되지 않는다.
- **Alternatives considered**: drop 후 재생성(Jira 원안 — 홈서버 데이터 전량 유실이라 기각), `flyway_schema_history` 에 수동 INSERT 로 세 파일을 적용된 척 기록(checksum 을 손으로 계산해야 해 취약 — 기각).
- **로컬 DB**: 개인 소유 — drop 후 재생성(간단) 또는 동일 재기준선, 개발자 선택.

## R6. Test-First 전략 (헌법 원칙 I)

- **Decision**: Red 진입점은 신규 리소스 가드 테스트 `MigrationLayoutTest`(BehaviorSpec, `:app:api` 테스트) — (a) `db/migration` 에 파일이 정확히 2개(init·마스터 시드)이고 init 에 INSERT 가 없다, (b) 마스터 시드의 기피물질 행이 81건이다, (c) `db/seed` 에 데모 시드가 존재하고 `db/migration` 어디에도 `INSERT INTO food` 가 없다(프로덕션 데모 유입 금지 가드). 파일이 없는 현 상태에서 Red → 파일 작성으로 Green. 기존 `AvoidanceCatalogSeedSyncTest` 는 경로만 새 마스터 시드 파일로 갱신(enum↔시드 정합 검증 지속). 스키마 자체는 전체 통합 테스트 스위트(MySQL Testcontainers + `ddl-auto=validate`)가 검증한다.
- **Rationale**: SQL 스쿼시는 단위 테스트 대상이 아니지만, "데모 시드가 prod 적용 위치에 없어야 한다"는 규칙은 리소스 테스트로 영구 가드할 수 있다.
- **Alternatives considered**: prod-locations `@SpringBootTest`(음식 0건 assert) — 컨텍스트 1개 추가 비용 대비 리소스 테스트와 중복 가드라 기각.

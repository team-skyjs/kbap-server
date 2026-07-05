# Data Model: MySQL Testcontainers 도입 (KB-46)

## 신규 데이터 모델 — 없음

이 기능은 **데이터 모델을 도입·변경하지 않는다.** 도메인 객체·JPA 엔티티·DB 스키마·Flyway 마이그레이션 SQL 은 그대로 두고, **통합 테스트가 실행되는 데이터베이스 환경만** H2(임베디드) → MySQL 8.4(Testcontainers)로 교체한다.

따라서 엔티티/필드/관계/상태전이 정의는 이 문서의 대상이 아니다.

## 오히려 이 작업이 "검증"하는 기존 데이터 모델 자산

본 작업의 P1 가치는 신규 모델 정의가 아니라, **이미 존재하지만 테스트에서 실행된 적 없는** 스키마 생성 자산을 실 엔진에서 검증하는 것이다:

- `app/api/src/main/resources/db/migration/*.sql` — 전체 Flyway 마이그레이션 체인
  - 테이블 생성/변경(`scan`, `food`, `avoidance_*`, `food_avoidance_substance` 등)
  - MySQL 전용 구문: `JSON`, `JSON_OBJECT/JSON_SET/JSON_OBJECTAGG`, `MODIFY COLUMN`, `ADD COLUMN … AFTER`, `AUTO_INCREMENT`
  - 진화 이력: `ingredient` 생성→시드→후속 마이그레이션 drop/replace(KB-40), 번역 컬럼→JSON 이관(KB-48)
- 엔티티↔스키마 정합 — `ddl-auto=validate` 로 `@JdbcTypeCode(SqlTypes.JSON)` Map 컬럼, `@Column(length=N)`, 소프트삭제 `status` 등이 실제 MySQL 스키마와 맞는지 검증

이들은 **변경 대상이 아니라 검증 대상**이다.

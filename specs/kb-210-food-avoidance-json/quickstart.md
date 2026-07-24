# Quickstart: 음식 기피성분 JSON 컬럼 이관

## 검증 시나리오

```bash
# 1. food 도메인 단위 테스트 (값 객체·정렬·위험도)
./gradlew :domain:food:test

# 2. api 통합 테스트 (상세 조회 계약 + 마이그레이션 백필 왕복 — Testcontainers MySQL)
./gradlew :app:api:test

# 3. 전체 (배치 컴파일 무결성 포함)
./gradlew build
```

## 핵심 확인 포인트

1. **상세 조회 계약 유지**: `GET /api/v1/foods/detail` 응답의 `ingredients` 가 포함 확률 내림차순 — JSON 저장 순서를 섞어 시드해도 동일해야 한다(애플리케이션 정렬 증명).
2. **백필 왕복**: Flyway 마이그레이션이 실제 MySQL(Testcontainers)에서 `food_avoidance_substance` ACTIVE 행을 `avoidance_substances` JSON 으로 옮기고, Hibernate 가 `FoodAvoidanceItem` 으로 역직렬화하는지 — 키(`inclusion_percent`) 불일치 시 여기서 잡힌다.
   - 주의: api 테스트 프로필은 Flyway enabled + `ddl-auto=validate` 라 마이그레이션이 컨텍스트 기동 시 **빈 Testcontainer** 에 1회 자동 실행된다 — 그 시점엔 `food_avoidance_substance` 가 비어 백필 UPDATE 가 no-op 이므로 일반 api 테스트로는 백필 로직이 검증되지 않는다. 따라서 백필 자체 검증은 마이그레이션 SQL 을 리소스로 읽어 백필 UPDATE 문만 수동 실행하는 전용 테스트(`FoodAvoidanceBackfillMigrationTest`)로 한다(`AvoidanceCatalogSeedSyncTest` 선례 — 파일명 패턴 결합 주의).
3. **빈 목록**: 매핑 0건 음식은 `[]` 로 백필되고 상세 조회·위험도 판정이 정상 동작.
4. **원본 보존**: 마이그레이션 후 `food_avoidance_substance` 행 수·내용 무변화.
5. **배치 무접촉**: `:app:batch` 소스 diff 0 — `FoodScoringSource`(읽기 전용)만 물고 있어 컴파일만 통과하면 된다.

## 리스크 메모

- 테스트 시드 전면 수정: food INSERT 를 쓰는 모든 시드(`FoodTestSeed`·`HomeTestSeed`·`ScenarioFoodSeed` 등)에 NOT NULL 컬럼 추가 필요 — 누락 시 INSERT 실패로 즉시 드러난다.
- 백필 이후 배치가 구 테이블에 쓰는 신규 데이터는 조회에 반영되지 않는 시차 존재(스펙 Assumptions 명시 — 배치 전환 후속 작업에서 해소).

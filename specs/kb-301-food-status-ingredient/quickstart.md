# Quickstart: KB-301 검증

```bash
./gradlew build                 # 전 모듈 컴파일+테스트 — 개명 누락은 여기서 전수 검출
./gradlew :common:test --tests "*Food*"          # 상태 전이 도메인 테스트
./gradlew :api:test --tests "*AdminFood*"        # 관리자 승인 플로우·필터
./gradlew :api:test --tests "*FoodDetail*"       # 사용자 응답 계약 불변 확인
```

- 통합 테스트는 MySQL Testcontainers 위에서 Flyway 전체 마이그레이션 적용 + `ddl-auto=validate` — 엔티티↔스키마(4값 ENUM·ingredient 컬럼) 정합이 자동 검증된다.
- 마이그레이션 매핑 검증: 구 상태 시드를 넣고 마이그레이션 후 신 상태 분포를 확인하는 테스트(구 상태 잔존 0건 — SC-001).
- 주의(메모리 `food-column-add-touchpoints`): 스캔 테스트 손스텁 CREATE TABLE·food INSERT 시드에 `avoidance_substances`·구 상태 문자열이 박혀 있다 — 전체 build 로만 잡히므로 모듈 단위 테스트 통과로 완료 선언하지 않는다.
```

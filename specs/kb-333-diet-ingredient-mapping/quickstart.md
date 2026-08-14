# Quickstart: diet 카테고리별 회피 재료 매핑 조회

## 테스트 실행

```bash
# 매핑 정합 단위 테스트 (Testcontainers 불필요 — 시드 SQL 파싱)
./gradlew :api:test --tests "com.kbap.api.ingredient.DietCategoryMappingSyncTest"

# MockMvc 통합 테스트 (MySQL Testcontainers — diets 시나리오 포함)
./gradlew :api:test --tests "com.kbap.api.ingredient.IngredientControllerTest"

# 전체 검증
./gradlew build
```

## 로컬 호출

```bash
./gradlew :api:bootRun   # SPRING_PROFILES_ACTIVE=local

curl -s "http://localhost:8080/api/ingredients/diets?lang=en" \
  -H "X-API-Version: 1.0" | jq '.payload.diets[] | {code, name, count: (.ingredients | length)}'
```

기대: 15개 카테고리, 예로 `GLUTEN_FREE` 는 재료 4개(id 26·28·29·30), `NO_ALCOHOL` 은 3개(78~80).

## 산출물 지도

- 매핑 단일 출처(번호→코드 변환표): `data-model.md`
- API 계약: `contracts/diet-api.md`
- 설계 결정·근거: `research.md` (R1 enum vs DB, R3 정합 검증 전략)

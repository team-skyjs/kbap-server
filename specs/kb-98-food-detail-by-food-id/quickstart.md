# Quickstart: 음식 상세 조회 foodId 정합 검증

## 자동 테스트 (권장)

```bash
# 상세 web 통합 테스트 (MockMvc + Testcontainers)
./gradlew :app:api:test --tests "com.meogo.app.api.food.*"

# 영속 어댑터 findById 테스트
./gradlew :infra:persistence:test --tests "com.meogo.infra.persistence.food.FoodRepositoryAdapterTest"

# 전체
./gradlew build
```

검증 포인트:
- foodId 성공: 시드 음식(id=1 된장찌개)을 `GET /api/v1/foods/1?lang=en` 로 조회 → 200 + 기존 상세 스키마.
- 미존재: `GET /api/v1/foods/999999` → 400, `해당 음식 정보 없음`.
- 소프트삭제: status=DELETED 음식 id → 400(@SQLRestriction 자동 제외).
- 비숫자: `GET /api/v1/foods/abc` → 400.
- 미지원 lang: `GET /api/v1/foods/1?lang=fr` → 400(지원 목록 안내).

## 로컬 수동 확인 (선택)

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :app:api:bootRun
# 목록에서 foodId 확인 후:
curl "http://localhost:8080/api/v1/foods/1?lang=en"
curl "http://localhost:8080/api/v1/foods/999999"   # 400
```

Swagger UI: http://localhost:8080/swagger-ui/index.html — `음식 상세` 태그에서 foodId 파라미터 확인.

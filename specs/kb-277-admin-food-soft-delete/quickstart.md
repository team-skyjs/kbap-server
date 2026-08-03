# Quickstart: 관리자 음식 삭제(소프트)

## 테스트 실행

```bash
./gradlew :api:test --tests "com.kbap.api.admin.AdminFoodServiceTest"
./gradlew :api:test --tests "com.kbap.api.admin.AdminFoodPageControllerTest"
```

Docker 필요(MySQL Testcontainers). ArchUnit 제외 실행: `-Dkotest.tags="!arch"`.

## 수동 확인

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
```

1. 관리자 로그인 후 `/admin/foods/list` → 아무 음식 상세보기.
2. 패널 하단 삭제 버튼 → confirm 다이얼로그(재시드 누락 경고 포함) → 확인.
3. 목록으로 redirect(같은 페이지), 삭제 완료 배너, 해당 음식 미노출 확인.
4. 앱 API 확인: 검색/상세(`GET /api/v1/foods/...`)에서 해당 음식이 사라졌는지 확인.

## 주의

- 스키마 변경 없음 — Flyway 마이그레이션을 만들지 않는다.
- 테스트에서 food row 를 물리 DELETE 로 정리할 때는 `food_review` 선삭제 필수(기존 함정 — `fk_food_review_*`).
- 시드 음식명은 스펙 간 유일하게(접두어) — `uq_food_korean_name`.

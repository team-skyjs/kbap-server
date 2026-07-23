# Quickstart: 배치 완성 콘텐츠 PENDING_REVIEW 전이

## 검증 순서 (TDD)

```bash
# 1. Red — 전이 테스트를 새 기대값(PENDING_REVIEW)으로 고치고 실패 확인
./gradlew :domain:food:test --tests "com.kbap.domain.food.model.*Transition*"

# 2. Green — FoodContentStatus·Food·FoodContentBatchConfig·Flyway 반영 후
./gradlew :domain:food:test :app:batch:test

# 3. 전체 회귀(ArchUnit·통합 포함 — Testcontainers 로 Docker 필요)
./gradlew build
```

## 수동 확인 (선택)

```bash
# 배치 실행 후 완성된 음식의 상태 확인 — READY 가 아니라 PENDING_REVIEW 여야 한다
./gradlew :app:batch:bootRun
# MySQL: select id, korean_name, content_status from food order by updated_at desc limit 10;
```

## 확인 포인트

- 4작업 완비 음식: `content_status = 'PENDING_REVIEW'` (READY 직행 없음)
- 홈 랜덤/목록/검색/스캔 이력 조회에 PENDING_REVIEW 음식 미노출
- 기존 READY 음식: 상태·노출 불변

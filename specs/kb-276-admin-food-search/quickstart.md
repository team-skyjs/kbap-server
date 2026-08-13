# Quickstart: 관리자 음식 목록 음식명 검색

## 검증 시나리오

```bash
./gradlew :api:bootRun   # SPRING_PROFILES_ACTIVE=local
```

1. `/admin/foods/list` 접속 → 기존 전체 목록 (변화 없음 확인).
2. 검색 폼에 "김치" 입력 → 음식명에 "김치"를 포함하는 음식만 표시.
3. 검색 상태에서 다음 페이지 → 검색 결과의 2페이지 (`?q=김치&page=2`).
4. 검색 결과에서 상세 → 편집 → 저장 → "김치" 검색 목록으로 복귀.
5. "없는음식명123" 검색 → 빈 목록 안내 + 전체 목록 복귀 링크.

## 테스트

```bash
./gradlew :api:test --tests "com.kbap.api.admin.AdminFoodServiceTest" \
                    --tests "com.kbap.api.admin.AdminFoodListControllerTest"
./gradlew :api:test   # 회귀 전체
```

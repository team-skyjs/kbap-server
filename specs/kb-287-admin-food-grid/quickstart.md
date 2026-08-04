# Quickstart: 관리자 음식 목록 카드 그리드 확인

## 실행

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
```

브라우저에서 `http://localhost:8080/admin/foods/list` (관리자 로그인 필요).

## 수동 확인 시나리오

1. **카드 그리드**: 목록이 정사각 카드 격자로 보이고, 각 카드에 썸네일(없으면 플레이스홀더)·음식명·상태 배지가 보인다. 목록 영역만 내부 스크롤되고 검색 폼·페이지네이션은 고정.
2. **상태 필터**: select 에서 `PENDING_REVIEW` 선택 → 해당 상태만 + 건수 갱신. `q` 와 동시 적용, 페이지 이동에도 유지. URL 에 `status=없는값` 을 손으로 넣어도 오류 없이 전체 목록.
3. **상세 모달**: 카드 상세보기 → 모달(백드롭 포함) 오픈, 읽기 전용. 편집 → 저장/취소, 삭제 → confirm. 닫기·ESC → 모달 닫힘 + 그리드 스크롤 위치 유지.
4. **버튼**: 모달 푸터 4버튼이 동일 규격·역할별 색(삭제=빨강)으로 보이는지 확인.

## 테스트

```bash
./gradlew :api:test --tests "com.kbap.api.admin.AdminFoodListControllerTest" \
                    --tests "com.kbap.api.admin.AdminFoodPageControllerTest"
./gradlew :api:test -Dkotest.tags='!arch'   # 전체 (Testcontainers MySQL 필요 — Docker 실행 중이어야 함)
```

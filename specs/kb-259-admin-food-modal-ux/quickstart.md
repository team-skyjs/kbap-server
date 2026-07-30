# Quickstart: 관리자 음식 상세 모달 UX 개선

## 검증 실행

```bash
./gradlew :api:test --tests "com.kbap.api.admin.AdminFoodListControllerTest" --tests "com.kbap.api.admin.AdminFoodPageControllerTest"
```

## 수동 확인 (local)

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
```

1. `/admin/foods/list` 접속 → 목록을 중간까지 스크롤 → 임의 행 "상세보기" 클릭.
2. 리로드 후 해당 행 위치로 스크롤된 채 모달이 열리는지 확인 (URL 에 `#food-<id>`).
3. 모달에서 이미지 확인 — `imageRef` 있는 음식은 실제 사진, 없는 음식은 플레이스홀더.
4. 값 수정 후 저장 → 목록이 같은 행 위치로 복귀(`updated` 배너 + `#food-<id>`).
5. JSON 필드에 잘못된 값 입력 후 저장 → 오류 배너와 함께 모달 재오픈, 뒤편 목록 위치 유지.

`kbap.storage.public-base-url` 이 비어 있는 프로필에선 이미지 URL 이 키 원문 그대로 나가 로드에 실패할 수 있다 — 이 경우 플레이스홀더 대체 표시가 정상 동작이다.

## 변경 파일

- `api/src/main/kotlin/com/kbap/api/admin/AdminFoodService.kt` — `imagePublicBaseUrl` 주입 + `AdminFoodDetailView.imageUrl`
- `api/src/main/kotlin/com/kbap/api/admin/AdminFoodPageController.kt` — redirect 5분기 `#food-<id>` fragment
- `api/src/main/resources/templates/admin/food-list.html` — 행 anchor·링크 fragment·모달 이미지/플레이스홀더
- `api/src/test/kotlin/com/kbap/api/admin/AdminFood{List,Page}ControllerTest.kt` — 검증 보강

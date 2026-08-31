# Quickstart: 관리자 음식 수정 안정성

## 변경 지점 (전부 `:api`)

1. `api/src/main/kotlin/com/kbap/api/admin/AdminFoodPageController.kt`
   - `foodList` 에 `@RequestParam(required = false) edit: Boolean?` 추가 → `model.addAttribute("editMode", detail != null && edit == true)`.
   - 저장 실패 리다이렉트 3종(invalid-name·invalid-json·duplicate-name)에 `&edit=true` 부착.
2. `api/src/main/resources/templates/admin/food-list.html`
   - 모달 전 입력에 `th:disabled="${!editMode}"`.
   - 저장 버튼 `th:if="${editMode}"`, '취소' 링크(편집 모드) / '편집' 링크(읽기 전용) 분기.
3. `api/src/main/kotlin/com/kbap/api/admin/AdminFoodService.kt`
   - `updateFood` 필드 대입 마지막에 `food.transitionByContentState()` 1줄.

## 테스트 (Red 선행 — 헌법 원칙 I)

- `AdminFoodListControllerTest`(MockMvc·BehaviorSpec): 기본 렌더에 `disabled` 존재·저장 버튼 부재·'편집' 링크 존재 / `edit=true` 렌더에 입력 활성·저장 버튼·'취소' 링크 / 실패 리다이렉트가 edit 유지.
- `AdminFoodServiceTest`: 검수 이전 상태 + 텍스트 완비(+이미지 유/무) 저장 → PENDING_REVIEW/PENDING_IMAGE 보정 · 텍스트 미완 → INCOMPLETE 보정 · READY/PENDING_REVIEW 수동 지정 → 유지 · 검증 실패 시 무보정.

## 실행·검증

```bash
./gradlew :api:test --tests "com.kbap.api.admin.*"   # 관련 테스트만
./gradlew build                                      # 전체 게이트
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun  # http://localhost:8080/admin/foods/list 수동 확인
```

수동 확인 시나리오: 상세보기 → 입력 회색(수정 불가)·저장 버튼 없음 → 편집 → 값 수정 → 취소 → 원값 복원 확인 → 편집 → 텍스트 완비 음식을 INCOMPLETE 인 채 저장 → 목록 배지가 PENDING_IMAGE/PENDING_REVIEW 로 보정됐는지 확인.

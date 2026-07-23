# Quickstart: 기피성분 조사 후보 밖 성분 항목 단위 스킵

**Feature**: kb-236-avoidance-item-skip

## 변경 파일 (2개)

| 파일 | 변경 |
|------|------|
| `infra/llm/src/main/kotlin/com/kbap/infra/llm/food/SpringAiFoodAvoidanceAssessmentClient.kt` | `parseValidOrNull` 항목 루프: 후보 밖 코드 → `valid=false; break` 대신 항목 스킵(continue) |
| `infra/llm/src/test/kotlin/com/kbap/infra/llm/food/SpringAiFoodAvoidanceAssessmentClientTest.kt` | 기존 "후보 밖 코드" 시나리오 기대값 갱신(85→57) + 신규 시나리오 추가 |

## TDD 순서 (헌법 원칙 I)

1. **Red**: 테스트 갱신·추가 후 실패 확인
   - 기존 `given("한 모델이 후보 밖 코드를 섞어 응답")`: then 을 새 정책으로 — 위반 모델도 유효로 종합, PORK `(80+90+0)/3 → 57`, spiciness `(3+4+5)/3 → 4`.
   - 신규 `given`(핵심): 최소 합의 1 + 단일 모델이 후보 밖 코드(예: OIL)를 섞어 응답 → 정상 항목·맵기만 종합, 예외 없음 (dev 실측 재현).
   - 신규 `given`: 후보 밖 코드만 있는 응답(정상 항목 0) + 유효 맵기 → 유효 응답, 성분 없음·맵기 종합.
   - 신규 `given`(경계): 후보 밖 코드가 percent 범위 밖(OIL 150)이거나 중복(OIL 2회) → 응답 무효 아님(스킵이 검증보다 먼저).
   - 신규 `given`(가드): 후보 안 코드 percent 범위 밖 → 여전히 응답 무효 (기존 중복·맵기·파싱 테스트는 무수정 통과 확인).

   ```bash
   ./gradlew :infra:llm:test --tests "com.kbap.infra.llm.food.SpringAiFoodAvoidanceAssessmentClientTest"
   ```

2. **Green**: `parseValidOrNull` 루프 변경 — 후보 밖이면 continue, 후보 안이면 기존 검사(percent 범위·중복) 유지.

3. **Refactor + 전체 검증**:

   ```bash
   ./gradlew build
   ```

## 검증 포인트

- 소비처(`:app:batch` FoodAvoidanceMapProcessor)는 무수정 — seam 시그니처·결과 타입 불변이므로 배치 테스트가 그대로 통과해야 한다.
- 프롬프트·저장 경로·상태 전이 무변경 (research.md Decision 4).

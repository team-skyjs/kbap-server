# Quickstart: 검증 절차

성공 기준(SC-001~005)을 명령으로 옮긴 순서다. 리팩토링 **전에** 한 번 실행해 그린을 확인하고(기준선), 구현 후 같은 명령으로 다시 확인한다.

```bash
# 기준선: 홈 통합 테스트가 현재 그린인지 (Kotest 는 --tests 필터를 무시하므로 api 모듈 전체가 돈다)
./gradlew :api:test

# SC-001: 홈 테스트 파일 무수정 — 구현 후 diff 가 비어야 한다
git diff --stat develop -- api/src/test/kotlin/com/kbap/api/home/
git diff --stat develop -- api/src/test/kotlin/com/kbap/api/home/HomeTestSeed.kt   # 시드도 불변

# SC-002: 홈 조합 서비스에 저장소 타입 0개·리뷰/스캔 서비스 0개
grep -n "Repository\|ReviewService\|ScanService" api/src/main/kotlin/com/kbap/api/home/HomeService.kt   # 결과 없어야 함

# SC-003: 섹션당 서비스 호출 1개 — 재조회·변환 반복이 홈에 없음
grep -n "associateBy\|mapNotNull\|FoodSummaryView.from\|AvoidedIngredientView(" api/src/main/kotlin/com/kbap/api/home/HomeService.kt   # 결과 없어야 함

# 이관 완료 — 옛 메서드가 사라졌는지
grep -rn "getMostReviewedFoodIds\|getRecentReadyFoodIds" api/src/main/kotlin   # 결과 없어야 함
grep -rn "AvoidedSubstanceView" api/src/main/kotlin                            # 결과 없어야 함(AvoidedSubstanceResponse 는 남는다)

# SC-004: api 모듈 전체(ArchUnit 포함) 그린
./gradlew :api:test

# SC-005: 외부 계약 불변 — 응답 DTO·경로 파일 diff 없음
git diff --stat develop -- api/src/main/kotlin/com/kbap/api/home/HomeResponse.kt api/src/main/kotlin/com/kbap/api/home/HomeController.kt
# HomeResponse.kt 는 import 한 줄(AvoidedIngredientView)만 바뀌어야 하고, HomeController.kt 는 diff 가 없어야 한다
```

기대 결과 요약

| 검사 | 기대 |
|------|------|
| 홈 테스트 두 파일 + 시드 diff | 없음 |
| `HomeService.kt` 의존 | `MemberService`·`IngredientService`·`FoodService` 3개 |
| `HomeService.getHome` 본문 | 활성 회원 해석 1줄 + `HomeResult(` 네 섹션 각 서비스 호출 1개 |
| `./gradlew :api:test` | BUILD SUCCESSFUL |

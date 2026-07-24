# Quickstart: 언어 무관 메뉴명 한국어 항상 포함

## 무엇을 하는가

상세·목록 응답에 `koreanName`(언어 무관 한국어 원문)을 추가한다. 지역화명과 같으면 `null`.

## 구현 순서 (TDD, 원칙 I — 각 단계 Red → Green)

1. **core:food** — `FoodContent.koreanName()` / `Food.koreanName()` seam 추가.
   - Red: `FoodTest`/`FoodContentTest` 에 "koreanName 은 지역화와 무관하게 한국어 원문을 반환한다" 케이스.
2. **application:client (상세)** — `GetFoodDetailResult.koreanName: String?` 추가, 유스케이스에서 `food.koreanName().takeIf { it != foodName }`.
   - Red: `GetFoodDetailUseCaseTest` — en 요청 시 koreanName=원문, ko/폴백 시 null.
3. **application:client (목록)** — `BrowseMenusResult.MenuSummaryView.koreanName` 추가, 동일 규약.
   - Red: `BrowseMenusUseCaseTest` — 항목별 koreanName 포함/ null.
4. **app:api** — `FoodDetailResponse`·`MenuSummaryResponse` 에 `koreanName: String?`(+`@Schema`) 추가, `from()` 미러링.
   - Red: `FoodDetailLangTest`·`MenuListControllerTest` (MockMvc BehaviorSpec) — en/ja/ko·미지원 폴백별 koreanName 검증(SC-003).

## 수동 확인 (선택)

로컬 docker MySQL + `SPRING_PROFILES_ACTIVE=local` 부팅 후:

```bash
curl "localhost:8080/api/v1/foods/detail?menuName=된장찌개&lang=en"   # koreanName="된장찌개"
curl "localhost:8080/api/v1/foods/detail?menuName=된장찌개&lang=ko"   # koreanName=null
curl "localhost:8080/api/v1/foods?lang=ja"                          # 각 item.koreanName
```

## 완료 기준

- [ ] 상세·목록 응답에 `koreanName` 노출, 동일 규약(FR-004).
- [ ] 지역화명=한국어일 때 `koreanName=null`(FR-003, SC-002).
- [ ] en/ja/ko·미지원 폴백 BehaviorSpec 통합 테스트 통과(SC-003).
- [ ] 기존 지역화명·폴백 응답 값 회귀 없음(SC-004).
- [ ] DB·Flyway·스캔 API 무변경 확인.

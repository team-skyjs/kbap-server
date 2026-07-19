# Quickstart: 리네임 검증 런북

순수 리네임이므로 "동작 무변경"이 핵심 검증이다.

## 1. 리네임 완료 판정 (SC-002)

서비스 public 조회 메서드에 `find` 접두가 남지 않았는지 확인(유비쿼터스 동사 `findOrSignUp` 제외):

```bash
grep -rn "fun find" domain application --include="*.kt" | grep -v "/test/" \
  | grep -v "findOrSignUp" | grep -v "JpaRepository.kt" | grep -v "private fun"
# 기대: 출력 없음
# (레포지토리의 findBy~ 는 Spring Data 파생 쿼리 계약, private 헬퍼는 규약 대상 밖)
```

## 2. 페이지 이름·타입 일치 (SC-003)

`~Page` 로 끝나는 조회 메서드는 `~Page` 타입을 반환해야 한다:

```bash
grep -rn "fun getFoodPage\|fun getBookmarkPage" domain --include="*.kt" | grep -v "/test/"
# 기대: 반환 타입이 각각 FoodPage / BookmarkPage
grep -rn "fun getFoods\b\|fun getFoodsByKeyword" domain --include="*.kt"
# 기대: internal, 반환 List<Food> (Page 접미 없음)
```

## 3. 전체 테스트 통과 (SC-001)

```bash
./gradlew test
# 기대: BUILD SUCCESSFUL — 전 모듈 Green
```

ArchUnit 경계만 빠르게:

```bash
./gradlew :app:api:test --tests "*ModuleBoundaryTest*"
```

## 4. API 동작 무변경 (SC-004)

컨트롤러 경유 조회의 응답이 리네임 전후 동일한지 확인(핵심 3 API):

- `GET /api/v1/foods` (browse→getFoodPage): 목록·nextCursor·hasNext·위험도 필드 동일
- `GET /api/v1/foods/search` (search→searchFoodPage): 검색 결과·페이징 동일
- `GET /api/v1/foods/detail` (getDetail): 상세 응답 동일
- `GET /api/v1/home` (findActive→getMemberOrNull): 게스트/회원 홈 응답 동일

기존 컨트롤러 통합 테스트(`FoodControllerTest`·`HomeControllerTest`·`BookmarkControllerTest`)가 Green 이면 계약 무변경이 증명된다.

## 5. 계약 이동 지점 검증 (getReadyFood)

`getReadyFood` 는 없으면 `FOOD_NOT_FOUND` 를 던진다(기존 `find + ?: throw` 와 동일 결과):

- `FoodServiceTest`: 미존재·미완성·소프트삭제 id → `shouldThrow<BusinessException>`(FOOD_NOT_FOUND)
- `GET /api/v1/foods/detail` 미존재 id → 기존과 동일한 에러 응답

## 6. 규약 문서 (FR-008)

`CLAUDE.md` "서비스 메서드 네이밍" 절이 get 통일 + `get~OrNull` 예외 조건 + 규약 밖(유비쿼터스·보조·행위) 구분을 담고 있는지 확인.

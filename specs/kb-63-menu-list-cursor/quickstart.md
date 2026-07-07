# Quickstart: 메뉴 목록 조회 (무한 스크롤, no-offset)

## TDD 순서 (원칙 I — Red → Green → Refactor)

계층 안쪽 → 바깥으로. 각 단계는 **실패 테스트 먼저** 작성·Red 확인 후 최소 구현.

1. **유스케이스 단위** — `BrowseMenusUseCaseTest` (페이크 `FoodRepository`·`AvoidedSubstanceProvider`·`AvoidanceSubstanceRepository`).
   - given 21개 중 20개 반환 → `hasNext=true`, `nextCursor=items.last().foodId`.
   - given 커서 지정 → `findMenuPage(cursor, 21)` 호출·최신순 유지.
   - given 결과 0개 → `items:[]`·`hasNext:false`·`nextCursor:null`.
   - given 사용자 회피 ∩ 성분 → food 별 `overallRiskStatus` 정확(소프트삭제 성분 제외).
   - given 미지원 lang → 예외(원칙 V), 미지정 → ko 표시명.

2. **영속 어댑터 슬라이스** — `FoodRepositoryAdapterTest` 보강 (MySQL Testcontainers, seed 다건).
   - keyset 정렬: `findMenuPage(null, 20)` 최신순 상위 20.
   - 커서 경계: `findMenuPage(cursorId, 20)` 는 `id < cursorId` 만.
   - 빈 결과: 커서가 최소 id 이하 → `[]`.
   - 소프트삭제 food 제외(ACTIVE 필터).

3. **web 통합** — `MenuListControllerTest` (MockMvc, `@SpringBootTest`).
   - 첫 페이지: 200·20개·`hasNext`·`nextCursor` 존재, `BaseResponse.success=true`.
   - 다음 커서로 연속 조회 → 중복 foodId 없음(불변식 1·2).
   - 빈 결과 200 / 마지막 페이지 `hasNext:false`.
   - 잘못된 커서 → 400 `success:false`.
   - `lang=en` 표시명 지역화 / 미지원 코드 400.

## 검증 커맨드

```bash
# 단위 (도메인/유스케이스)
./gradlew :application:client:test --tests "com.meogo.application.client.food.usecase.BrowseMenusUseCaseTest"

# 영속 (Testcontainers — Docker 필요)
./gradlew :infra:persistence:test --tests "com.meogo.infra.persistence.food.FoodRepositoryAdapterTest"

# web 통합
./gradlew :app:api:test --tests "com.meogo.app.api.food.MenuListControllerTest"

# 경계(모듈 의존 방향) 무손상 확인
./gradlew :app:api:test --tests "com.meogo.app.api.architecture.ModuleBoundaryTest"

# 전체
./gradlew build
```

## 수동 확인 (로컬)

```bash
# 로컬 docker MySQL + 앱 (IntelliJ 실행 중이면 8080 점유 주의 — 새로 띄우지 말 것)
# SPRING_PROFILES_ACTIVE=local

# 첫 페이지
curl "http://localhost:8080/api/v1/foods"

# 다음 페이지 (위 응답의 nextCursor 사용)
curl "http://localhost:8080/api/v1/foods?cursor=<nextCursor>&lang=en"
```

Swagger UI: `http://localhost:8080/swagger-ui/index.html` — "음식 목록" 태그.

## 주의 / 함정

- **컬렉션 fetch-join + limit 금지**: 반드시 2단계(id keyset → id-in fetch join). 한 쿼리로 하면 인메모리 페이징(HHH000104).
- **정렬 방향 일관**: id 조회·id-in 조회 모두 `order by f.id desc`. asc 재사용 시 카드 순서가 뒤집힘.
- **상세 연결은 KB-98**: 목록의 foodId 로 상세를 곧장 호출하는 end-to-end 는 KB-98(상세 foodId 정합) 머지 후 완성. 본 태스크 테스트는 목록 자체만 검증.
- **위험도 의미 일관**: 상세와 동일하게 `avoidedCodes ∩ 카탈로그존재코드` 로 계산(소프트삭제 성분 미반영).
- **신규 마이그레이션 없음**: 스키마 변경 불필요. Flyway 파일 추가 금지.
- **Kotlin 주석 금지**(고정) · **BehaviorSpec 한국어 given/when/then**(고정).

# Quickstart: 음식별 기피 성분 직접 매핑 검증

TDD(헌법 I) 로 층별 실패 테스트를 먼저 작성한다. 모두 Kotest `BehaviorSpec`(given/when/then 한국어).

## 1. 도메인 (`:core:food`, 단위)

```bash
./gradlew :core:food:test
```

- `FoodAvoidanceSubstance`: `inclusionProbability` 1·100 경계 통과, 0·101·음수 → `IllegalArgumentException`; `substanceCode` blank → 예외.
- `Food.avoidanceSubstancesByProbability()`: 확률 내림차순 정렬 검증. 빈 목록 음식 유효.

## 2. 영속 (`:infra:persistence`, H2 통합)

```bash
./gradlew :infra:persistence:test
```

- `FoodRepositoryAdapter.findByKoreanName`: `food_avoidance_substance` 를 fetch join 1회로 함께 로드(포함 성분 개수 무관 상수 쿼리, N+1 없음) → 도메인 `avoidanceSubstances` 복원.
- 조합 유일(`food_id`,`substance_code`) 위반 시 저장 거부.
- 재료 관련 엔티티/리포지토리 제거 후 컴파일·컨텍스트 로딩 정상.

## 3. 유스케이스 (`:application:client`, 단위 — Fake 리포지토리)

```bash
./gradlew :application:client:test
```

- `GetFoodDetailUseCase`: Food 의 `avoidanceSubstances` → `AvoidanceSubstanceRepository.findByCodes` 로 표시명 해석 → 내부 `AvoidanceSubstanceView`(name/iconRef=null/inclusionProbability/riskStatus) 조립, 확률 내림차순.
- ko 요청: 번역 미조회, 한국어 표시명. 비-ko: 번역 표시명, 부재 시 ko 폴백.
- 포함 성분 0개 → 빈 목록 결과.

## 4. 웹 계약 (`:app:api`, MockMvc 통합)

```bash
./gradlew :app:api:test
```

- `GET /api/v1/foods/detail`: 응답 JSON 구조 **동결** 확인 — `payload.ingredients[].{name,iconRef,inclusionPercent,riskStatus}` 키·타입 유지.
- `inclusionPercent` 에 포함 확률(1~100)이 실림. `iconRef` null. 정렬 내림차순.
- 미지원 언어코드 → 400 + 지원 목록(기존 동작 회귀 없음).
- `FoodTestSeed` 를 재료 시드 → 음식-기피성분 시드로 교체.

## 5. 경계·전체

```bash
./gradlew :app:api:test --tests "com.meogo.app.api.architecture.ModuleBoundaryTest"
./gradlew build
```

- ArchUnit: `:core:food` 가 `:core:avoidance`/JPA/Spring 을 참조하지 않음(경계 유지).
- 전체 그린 + `flyway` V7 적용(로컬/dev)으로 시드 이행·재료 테이블 DROP 확인.

## 수동 확인 (선택)

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :app:api:bootRun
# 다른 셸에서
curl "http://localhost:8080/api/v1/foods/detail?menuName=된장찌개&lang=en"
# → payload.ingredients 가 포함 기피 성분 목록(확률·null 아이콘)으로 응답, 구조 동일
```

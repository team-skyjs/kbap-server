# Quickstart: 리뷰 식당(장소) 검색·선택 저장 (kb-274)

## 구현 순서 (TDD — 헌법 I)

**검색(US1)**:
1. **Red**: `PlaceControllerTest`(seam 페이크 — 정상 매핑·빈 query 400·미인증 401) + `KakaoPlaceSearchClientTest`(카카오 JSON 매핑·실패 시 PLACE-001) 작성 → 실패 확인.
2. **Green**: `common.port.place` seam → `:infra:place` 모듈(카카오 RestClient) → `api.place` 컨트롤러·응답 DTO → api 조립(`PlaceConfig`·build.gradle.kts·WebConfig 보호 경로·ErrorCode PLACE-001).

**저장·조회·수정(US2~US4)**:
1. **Red**: `ReviewPlace` 값 객체 단위 테스트 + `ReviewControllerTest` 장소 포함/미포함 작성·수정 교체/제거·길이 초과 400 → 실패 확인.
2. **Green**: Flyway 마이그레이션 → `ReviewPlace` `@Embeddable` → `Review.place` + `update` 확장 → 요청/응답 DTO → `ReviewService` 전달.
3. **Refactor + 검증**: 전체 테스트·ArchUnit 통과.

## 빠른 검증

```bash
./gradlew :common:test --tests "*ReviewPlaceTest*"
./gradlew :infra:place:test
./gradlew :api:test --tests "*PlaceControllerTest*" --tests "*ReviewControllerTest*"
./gradlew build          # 전체 회귀(신규 모듈 포함, Flyway + ddl-auto=validate 정합 포함)
```

- api 통합 테스트는 Flyway on + Hibernate `ddl-auto=validate` — 마이그레이션·엔티티 컬럼 불일치는 부팅 실패로 즉시 드러난다.
- 검색 통합 테스트는 `PlaceSearchClient` 페이크(`@TestConfiguration` `@Primary`)로 외부 호출 없이 실행 — 카카오 키 불필요.
- 마이그레이션 파일명은 **생성 시점 timestamp** `Vyyyy.MM.dd.HH.mm.ss__food_review_place_columns.sql`(정수 버전 금지).

## 수동 확인 (선택)

```bash
KBAP_KAKAO_REST_API_KEY=<키> SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
# Swagger UI: GET /api/v1/places?query=한밥집 → 결과 항목을 그대로 POST /api/v1/reviews 의 place 로 전달 → 응답 place 확인
```

## 주의점

- **신규 모듈 `:infra:place`** — `settings.gradle.kts` include + `kbap.spring-conventions` 플러그인 + api `build.gradle.kts` 의 `"implementation"(project(":infra:place"))`. 모듈 build 파일 의존성은 문자열 표기.
- **보호 경로 등록** — `WebConfig` 의 `JwtAuthenticationFilter` 패턴에 `/api/v1/places`·`/api/v1/places/*` 추가(누락하면 미인증 접근 허용 — 조용한 보안 결함).
- **`ErrorCode` 채번** — `PLACE-001`(502). `ErrorCodeStatusTest` 가 형식·유일성을 강제한다.
- `Review.update` 시그니처가 바뀌므로 기존 호출부(ReviewService.updateReview) 컴파일 에러를 함께 수정.
- 테스트 시드(FoodTestSeed·HomeTestSeed 등)의 `food_review` INSERT 는 컬럼 목록 명시라 nullable 컬럼 추가에 영향 없음 — 손대지 않는다.
- `common.port.place` 는 Spring-free 유지(ArchUnit 이 port 의 Spring/JPA 의존을 금지).

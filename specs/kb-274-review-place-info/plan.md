# Implementation Plan: 리뷰 작성 시 식당(장소) 검색·선택 저장

**Branch**: `kb-274-review-place-info` | **Date**: 2026-08-01 (개정: 검색 API 서버 제공 반영) | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-274-review-place-info/spec.md`

## Summary

두 갈래다. (A) **장소 검색 — 별도 컨트롤러**: `com.kbap.api.place` 기능 패키지에 검색 전용 컨트롤러를 신설하고, 서버가 카카오 로컬 키워드 검색 REST API 를 대신 호출해 결과 목록을 반환한다. 외부 시스템이므로 헌법 seam 규약을 따른다 — 계약 `common.port.place.PlaceSearchClient` + 구현 `:infra:place`(카카오 RestClient) + 조립 api config. (B) **리뷰 저장 — 기존 컨트롤러의 최소 변경**: 작성·수정 요청에 선택 `place` 를 받아 `food_review` 의 nullable 컬럼 5개(`@Embeddable ReviewPlace`)로 저장하고 `ReviewResponse.from` 단일 지점에 place 를 추가해 모든 조회에 자동 전파한다. 리뷰 컨트롤러의 변경은 선택 장소값 저장 하나뿐이다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM(Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), springdoc-openapi, Flyway(+flyway-mysql). 검색: Spring `RestClient`(web 스타터 포함 — 신규 라이브러리 없음) + 카카오 로컬 키워드 검색 REST API(`GET https://dapi.kakao.com/v2/local/search/keyword.json`, `Authorization: KakaoAK <REST_API_KEY>`)

**Storage**: MySQL — 기존 `food_review` 테이블(KB-128)에 nullable 컬럼 추가. 스키마 owner = `:api` Flyway. **검색 결과는 무영속**(일시 데이터)

**Testing**: JUnit 5 + Kotest `BehaviorSpec` · `@SpringBootTest` + MockMvc · MySQL Testcontainers. 검색 통합 테스트는 **seam 페이크**(`PlaceSearchClient` 테스트 구현)로 외부 호출 없이 검증(LLM `LlmModelCaller` 페이크 선례 — 헌법 I), 카카오 응답 매핑은 infra 단위 테스트

**Target Platform**: Linux 서버(web bootJar `:api`)

**Project Type**: web-service (Gradle 멀티모듈 모듈러 모놀리스 — 모듈 1개 신설: `:infra:place`)

**Performance Goals**: 리뷰 작성·조회는 기존과 동등(추가 쿼리 0). 검색은 카카오 왕복 1회 — 수 초 내 응답(SC-001)

**Constraints**: 기존 리뷰 흐름 무회귀. 카카오 장애가 리뷰 작성·조회에 전파되지 않아야 함(검색 창구 분리로 달성). 검색 자격증명(REST 키)은 서버 프로퍼티(`kbap.kakao.rest-api-key`)로만 보관 — 클라이언트 미노출. `:batch` 영향 없음

**Scale/Scope**: 변경 파일 ~15개 — `:common`(review 도메인 + `port.place` seam) · `:infra:place` 신설(어댑터+빌드 2~3파일) · `:api`(place 기능 패키지 3~4파일 + review DTO 3종 + WebConfig 보호 경로 + ErrorCode 1건) + Flyway 1건 + 테스트

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS | 스토리마다 실패 테스트 선작성: 검색(컨트롤러 — seam 페이크·400·401 / infra — 카카오 응답 매핑), 저장·조회·수정(장소 포함/미포함·교체/제거·길이 초과 400). Red 확인 후 구현. |
| II. Bounded Contexts | PASS | 장소 정보는 review 컨텍스트 소유(`common.domain.review.model`). 검색은 도메인이 아니라 **외부 seam**(`common.port.place`) — 도메인 간 의존 신설 없음, `ModuleBoundaryTest` 허용 맵 변경 불필요. api 검색 창구는 `com.kbap.api.place` 기능 패키지. |
| III. Layered Dependency Direction | PASS | 신설 의존은 규약 방향 그대로: `:infra:place` → `:common`(계약 구현), `:api` → `:infra:place`(조립 — `implementation`). 카카오 클라이언트는 seam 인터페이스로만 사용(`PlaceSearchClient`), 구현 직접 참조는 api 조립 config 만. `common.port.place` 는 Spring·JPA 무의존(ArchUnit 검사 대상). |
| IV. Persistence Ownership | PASS | 엔티티=도메인 모델 유지 — `ReviewPlace` 는 `@Embeddable` 값 객체(JPA 연관관계 아님). 검색 결과는 무영속. FK·제약은 Flyway 소유, 기존 `@Transactional` 경계 불변. |
| V. Domain Content Language Policy | PASS | 장소 정보·검색 결과는 음식 콘텐츠가 아님 — 번역 정책(9개 언어) 비적용, `lang` 무관. 검색어 검증(빈 값 400)은 요청 경계(web DTO/파라미터)가 소유 — 원칙 V 의 검증 소유 조항 준수. |

**Post-Design Re-check (Phase 1 완료 후)**: PASS — 신설 모듈·seam 이 기존 인프라 어댑터 4종과 동일 패턴(계약 `:common` / 구현 `:infra:*` / 조립 부트앱 config)이라 위반 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-274-review-place-info/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   ├── place-search-api.md   # Phase 1 output — 장소 검색 API 계약(신규 엔드포인트)
│   └── review-place-api.md   # Phase 1 output — 리뷰 API 요청/응답 델타 계약
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/
├── domain/review/model/Review.kt        # [수정] @Embedded place + update 시그니처 확장
├── domain/review/model/ReviewPlace.kt   # [신규] @Embeddable 값 객체
└── port/place/PlaceSearchClient.kt      # [신규] 검색 seam 계약 + 결과 값 타입 FoundPlace (Spring-free)

infra/place/                             # [신규 모듈] :infra:place — settings.gradle.kts 등록
├── build.gradle.kts                     # kbap.spring-conventions + :common 의존
└── src/main/kotlin/com/kbap/infra/place/
    └── KakaoPlaceSearchClient.kt        # 카카오 로컬 키워드 검색 RestClient 구현

api/
├── build.gradle.kts                     # [수정] "implementation"(project(":infra:place"))
└── src/main/kotlin/com/kbap/api/
    ├── place/                           # [신규] 검색 기능 패키지 — 별도 컨트롤러
    │   ├── PlaceController.kt           # GET /api/v1/places (검색)
    │   ├── PlaceApi.kt                  # swagger 문서 인터페이스
    │   └── PlaceSearchResponse.kt       # 응답 DTO
    ├── core/config/WebConfig.kt         # [수정] JwtAuthenticationFilter 보호 경로에 /api/v1/places 추가
    ├── core/config/PlaceConfig.kt       # [신규] KakaoPlaceSearchClient 조립(키 프로퍼티 kbap.kakao.rest-api-key)
    ├── review/ReviewCreateRequest.kt    # [수정] place 중첩 DTO(선택) — Create·Update 공용 ReviewPlaceRequest
    ├── review/ReviewResponse.kt         # [수정] place 필드 + from 매핑
    └── review/ReviewService.kt          # [수정] create/update 에 place 전달

common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt  # [수정] PLACE-001(외부 장소 검색 실패) 채번

api/src/main/resources/db/migration/
└── V<timestamp>__food_review_place_columns.sql   # [신규] nullable 컬럼 5개

테스트:
├── common/src/test/.../review/ReviewPlaceTest.kt          # [신규] 값 객체 검증
├── infra/place/src/test/.../KakaoPlaceSearchClientTest.kt # [신규] 카카오 응답 매핑
├── api/src/test/.../place/PlaceControllerTest.kt          # [신규] 검색 통합(seam 페이크·400·401)
└── api/src/test/.../review/ReviewControllerTest.kt        # [수정] 저장·수정 케이스
```

**Structure Decision**: 검색은 신규 기능 패키지(`api.place`) + 신규 어댑터 모듈(`:infra:place`)로 기존 인프라 seam 패턴을 복제하고, 리뷰 저장은 기존 review 컨텍스트·기능 패키지를 최소 확장한다.

## Complexity Tracking

위반 없음 — 모듈 신설(`:infra:place`)은 헌법 III 이 요구하는 외부 시스템 격리 패턴 그대로다(단순 대안인 api 내 직접 RestClient 는 seam 규약 위반이라 기각).

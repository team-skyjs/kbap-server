# Implementation Plan: 온보딩 재료 81종 이름·이미지 공개 조회

**Branch**: `kb-326-ingredient-images` | **Date**: 2026-08-11 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-326-ingredient-images/spec.md`

## Summary

온보딩(비로그인)에서 기피 재료 선택 시 재료 81종의 이름·사진을 보여주기 위해 — (1) `ingredients` 테이블에 `image_path` 컬럼을 추가하고 S3 실물 키(`images/webp/<code소문자>.webp`)를 단일 파생 UPDATE 로 시드하며, (2) 인증 없이 호출 가능한 `GET /api/ingredients?lang=<code>` 를 신설해 code·언어별 이름·완성 이미지 URL 목록을 반환한다. 새 도메인 서비스 없이 `com.kbap.api.ingredient` 기능 패키지 + 기존 `IngredientJpaRepository`·`displayName`·`ImageUrls` 재사용.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Gradle toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), springdoc-openapi, Flyway(+mysql)

**Storage**: MySQL (`ingredients` 테이블 — 컬럼 1개 추가). 이미지 실물은 S3(기존 업로드 완료, 코드 밖)

**Testing**: Kotest BehaviorSpec + MockMvc + MySQL Testcontainers (Flyway on, `ddl-auto=validate`)

**Target Platform**: Linux 서버 (api bootJar) — 모바일 앱 온보딩 화면이 소비

**Project Type**: web-service (Gradle 멀티모듈 모듈러 모놀리스 — `:api`·`:common` 만 변경)

**Performance Goals**: 81행 단순 findAll — 별도 목표 불필요(단일 쿼리, 페이지네이션 없음)

**Constraints**: 공개 엔드포인트(JWT 보호 경로 미등록), lang 필수(헌법 V), 기존 시드 마이그레이션 수정 금지

**Scale/Scope**: 재료 81종 고정 카탈로그, 신규 엔드포인트 1개, 마이그레이션 1건

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | ✅ PASS | tasks 를 Red→Green→Refactor 로 구성 — 컨트롤러(MockMvc)·시드 전수 검증 테스트를 구현보다 먼저 작성 |
| II. Bounded Contexts | ✅ PASS | 기존 `common.domain.ingredient` 컨텍스트 확장 + `com.kbap.api.ingredient` 기능 패키지 신설. 도메인 간 신규 의존 없음 — ArchUnit 허용 맵 변경 불필요 |
| III. Layered Dependency Direction | ✅ PASS | api → common 단방향만 사용. infra seam 신규 없음(이미지는 URL 조합만 — `kbap.storage.public-base-url` 프로퍼티) |
| IV. Persistence Ownership | ✅ PASS | 컬럼은 소유 도메인 엔티티(`Ingredient`)에, 스키마는 api Flyway 가 소유. 도메인 로직 없는 단순 목록이라 리포지토리 직접 사용 + API 서비스가 `@Transactional(readOnly = true)` 경계 소유(위임 창구 도메인 서비스 신설 안 함). JPA 연관 없음 |
| V. Domain Content Language Policy | ✅ PASS | lang 필수(빈 값 400)·미지원→EN·번역부재→KO 폴백 — 기존 `displayName`/`LocalizedText` 재사용. `imagePath` 는 콘텐츠 데이터라 DB 단일 출처(enum 에 안 넣음 — 고정 reference taxonomy 규칙 준수) |

**Post-Phase-1 re-check**: ✅ 설계 산출물(data-model·contracts)이 위 판정을 바꾸지 않음 — 위반 없음, Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-326-ingredient-images/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — S3 키 패턴·컬럼·공개 방식·lang·배치 결정 8건
├── data-model.md        # Phase 1 — Ingredient 컬럼 추가·마이그레이션·응답 모델
├── quickstart.md        # Phase 1 — 검증 명령·수동 확인·함정
├── contracts/
│   └── ingredients-api.md  # GET /api/ingredients 계약
└── tasks.md             # Phase 2 (/speckit-tasks — 이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/ingredient/
└── model/Ingredient.kt                  # [수정] imagePath 필드 추가

api/src/main/
├── kotlin/com/kbap/api/ingredient/      # [신설] 기능 패키지
│   ├── IngredientApi.kt                 # swagger 문서 인터페이스
│   ├── IngredientController.kt          # GET /api/ingredients (ApiPaths.API + "/ingredients")
│   ├── IngredientQueryService.kt        # findAll(id asc) + displayName + ImageUrls.resolve
│   └── IngredientListResponse.kt        # ingredients[{code,name,imageUrl}]
└── resources/db/migration/
    └── V<timestamp>__ingredient_image_path.sql   # [신설] 컬럼 추가 + 파생 시드

api/src/test/kotlin/com/kbap/api/ingredient/
├── IngredientControllerTest.kt          # [신설] MockMvc — 비인증 200/81건/lang 400/언어 폴백/무효 토큰 허용
└── (IngredientCatalogSeedSyncTest.kt)   # [기존 — 무변경] 리소스 경로 결합 영향 없음
```

**Structure Decision**: 기존 모듈러 모놀리스 구조 그대로 — `:common`(엔티티 1필드)·`:api`(기능 패키지 1개 + 마이그레이션 1건)만 변경. `WebConfig` 는 **건드리지 않는다**(JWT 보호 경로 미등록 = 공개가 의도 — quickstart 함정 참조). OpenAPI 스냅샷(`OpenApiSnapshotTest`)은 엔드포인트 추가로 갱신 필요(R8).

## Complexity Tracking

> 위반 없음 — 해당 없음.

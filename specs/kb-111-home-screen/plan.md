# Implementation Plan: 홈 화면 조회 — 기피 성분·인기 음식 5개·최근 스캔 10개

**Branch**: `kb-111-home-screen` | **Date**: 2026-07-12 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-111-home-screen/spec.md`

## Summary

`GET /api/v1/home` 단일 호출로 세 섹션(회원 기피 성분·인기 음식 5개·최근 스캔 10개)을 한 응답으로 내려준다. **선택적 인증**(토큰 없으면 비회원, 토큰이 있는데 무효/만료면 401)을 신규 도입하고, 지금까지 고정 5개를 반환하던 `MockAvoidedSubstanceProvider` 를 **회원 프로필 기반 구현**으로 교체한다(음식 위험도 판정이 회원별로 정확해짐). 최근 스캔의 원천이 없으므로 **스캔 이력 기록**(`:core:scan` 컨텍스트 + `scan_history` 테이블)을 선행 신설하고, 스캔 시 매칭된 완성(READY) 음식을 회원별로 적재한다. 인기 음식은 지표 부재로 카탈로그에서 무작위 5개를 반환하되 응답 계약(`FoodSummaryView`)은 고정한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web/validation/data-jpa), jjwt(파싱), springdoc

**Storage**: MySQL(운영) / MySQL 8.4 Testcontainers(통합 테스트). Flyway 마이그레이션. member 프로필은 `member.profile` JSON 컬럼, food 는 `food` + `content_status`(READY/INCOMPLETE)

**Testing**: JUnit5 + Kotest `BehaviorSpec`, 유스케이스 페이크 단위 + MockMvc·Testcontainers 통합

**Target Platform**: Linux 서버 (web bootJar `:app:api`)

**Project Type**: 모듈러 모놀리스 web 백엔드

**Performance Goals**: 표준 모바일 API 응답. 인기 음식 `ORDER BY RAND()` 는 현 카탈로그 규모에서 허용(지표 도입 시 정렬만 교체 — research R4)

**Constraints**: 외부 호출을 DB 트랜잭션 안에서 길게 잡지 않음(스캔 이력 기록은 매칭 완료 후 단발 write). 도메인/영속 모델을 API 로 직접 노출 금지 → `HomeResponse`/`FoodSummaryView` DTO 로만

**Scale/Scope**: 신규 엔드포인트 1개, 신규 모듈 1개(`:core:scan`), 신규 테이블 1개(`scan_history`), 프로바이더 교체 파급(food browse/search/detail/scan 4개 유스케이스 + 컨트롤러 선택 인증)

## Constitution Check

*GATE: Phase 0 이전 통과 필수. Phase 1 이후 재검.*

- **I. Test-First (NON-NEGOTIABLE)**: 각 task 는 실패 테스트(Red) 선작성. 유스케이스=페이크 `MemberRepository`/`FoodRepository`/`ScanHistoryRepository` 단위, 컨트롤러=MockMvc+Testcontainers(회원/비회원/무효토큰/빈케이스/스캔 dedup). ✅ 계획됨
- **II. Bounded Contexts**: 최근 스캔 이력은 별도 컨텍스트 `:core:scan`(문서상 이미 예약된 active 컨텍스트)에 둔다. 홈의 컨텍스트 조합(member 언어/기피 + food 인기/스캔 + avoidance 이름)은 **`:application:client` 에서만** 수행. 컨텍스트 간 참조는 ID·코드 값으로만(memberId·foodId·코드 문자열). ✅
- **III. Layered Dependency Direction**: `:app:api` → `:application:client` → `:core:*` → `:core:kernel`. 선택 인증 리졸버는 `:app:api` 에서 `TokenParser`(application) 를 주입. 프로바이더는 port(`MemberRepository`)로만 member 접근. ✅
- **IV. Persistence Encapsulation**: `scan_history` JPA 엔티티·리포지토리·adapter 는 `:infra:persistence` 에만. `ScanHistoryRepository` port 는 `:core:scan`. 변환은 엔티티의 `toDomain`/`from`. ✅
- **V. Domain Content Language Policy**: 홈 응답은 회원 `appLanguage`, 비회원·미완료회원 `en`. 음식 이름/기피 성분 이름 번역·ko 폴백은 기존 `LocalizedText.resolve`/`displayName` 재사용. ✅

**게이트 결과**: 위반 없음 → Complexity Tracking 공란.

## Project Structure

### Documentation (this feature)

```text
specs/kb-111-home-screen/
├── plan.md              # 본 파일
├── research.md          # Phase 0 — 설계 결정(선택 인증·프로바이더 교체·스캔 이력·무작위·언어)
├── data-model.md        # Phase 1 — ScanHistory 도메인 + scan_history 테이블 + Home DTO
├── contracts/
│   └── home-api.md      # GET /api/v1/home 요청/응답 계약
├── quickstart.md        # Phase 1 — 로컬 검증 시나리오
├── checklists/
│   └── requirements.md  # spec 품질 체크리스트(완료)
└── tasks.md             # /speckit-tasks 산출(본 명령이 만들지 않음)
```

### Source Code (repository root)

```text
settings.gradle.kts                       # (수정) ":core:scan" include 추가

core/scan/                                # (신규) 스캔 이력 컨텍스트 — ORM/Spring-free
├── build.gradle.kts                      #   id("meogo.domain-conventions")
└── src/main/kotlin/com/meogo/core/scan/
    ├── ScanHistory.kt                    #   @AggregateRoot — memberId·foodId·scannedAt, record(...) 팩토리
    └── ScanHistoryRepository.kt          #   port: saveAll / findRecentReadyFoodIds(memberId, limit)

core/food/src/main/kotlin/com/meogo/core/food/
└── FoodRepository.kt                     # (수정) findRandomReady(size), findAllReadyByIds(ids)

application/client/src/main/kotlin/com/meogo/application/client/
├── home/
│   ├── HomeQueryUseCase.kt               # (신규) 3섹션 조합(@Transactional readOnly)
│   └── dto/
│       ├── HomeResult.kt                 #   avoidedSubstances?·popularFoods·recentScans?
│       └── AvoidedSubstanceView.kt       #   code + localized name
├── food/usecase/
│   ├── AvoidedSubstanceProvider.kt       # (수정) avoidedCodes(memberId: Long?)
│   ├── MemberAvoidedSubstanceProvider.kt # (신규, Mock 대체) 프로필 기반 — MemberRepository 주입
│   ├── MockAvoidedSubstanceProvider.kt   # (삭제)
│   ├── BrowseFoodsUseCase.kt             # (수정) memberId 전달
│   ├── SearchFoodsUseCase.kt             # (수정)
│   └── GetFoodDetailUseCase.kt           # (수정)
├── food/dto/*Input.kt                    # (수정) memberId: Long? 추가
└── scan/
    ├── usecase/ScanUseCase.kt            # (수정) memberId 수신 + 매칭 READY 음식 이력 기록
    └── dto/ScanInput.kt                  # (수정) memberId: Long?

infra/persistence/src/main/kotlin/com/meogo/infra/persistence/
├── scan/                                 # (신규)
│   ├── ScanHistoryJpaEntity.kt           #   BaseEntity 상속(scannedAt=createdAt 재사용)
│   ├── ScanHistoryJpaRepository.kt       #   findRecentReadyFoodIds 네이티브(dedup·order·READY join)
│   └── ScanHistoryRepositoryAdapter.kt
└── food/FoodJpaRepository.kt             # (수정) findRandomReadyIds + 기존 findByIdIn 재사용

app/api/src/main/kotlin/com/meogo/app/api/
├── home/
│   ├── HomeApi.kt / HomeController.kt    # (신규) GET /api/v1/home, @AuthMemberIdOrNull
│   └── HomeResponse.kt                   # (신규) BaseResponse<HomeResponse>
├── common/auth/
│   ├── AuthMemberIdOrNull.kt             # (신규) 선택 인증 파라미터 애너테이션
│   ├── AuthMemberIdOrNullArgumentResolver.kt # (신규) 헤더없음→null / 무효·만료→401
│   └── WebMvcAuthConfig.kt               # (수정) OrNull 리졸버 등록(TokenParser 주입)
├── food/*Controller.kt                   # (수정) @AuthMemberIdOrNull 전달
└── scan/ScanController.kt                # (수정) @AuthMemberIdOrNull 전달

app/api/src/main/resources/db/migration/
└── V2026.07.12.HH.mm.ss__create_scan_history_table.sql   # (신규)
```

**Structure Decision**: 기존 모듈러 모놀리스 구조를 그대로 따른다. 신규 컨텍스트 `:core:scan` 은 CLAUDE.md·헌법 II 가 이미 active 컨텍스트로 예약해 둔 자리를 채우는 것이라 구조 확장이 아니다. 인기 음식·최근 스캔 항목은 기존 메뉴 목록/검색이 쓰는 `FoodSummaryView` 를 재사용해 응답 계약을 단일화한다.

## Complexity Tracking

> Constitution Check 위반 없음 — 비워 둔다.

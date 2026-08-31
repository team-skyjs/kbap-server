# Implementation Plan: 앱 버전 정보 조회 (최소 지원·최신 버전과 스토어 링크)

**Branch**: `kb-329-app-version-check` | **Date**: 2026-08-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-329-app-version-check/spec.md`

## Summary

무인증 공개 GET API 가 최소 지원 버전·최신 버전·플랫폼별 스토어 링크를 단일 응답으로 내려주고, 관리자는 admin API 로 이 값을 코드 배포 없이 갱신한다. 값은 DB 단일 행(`app_version` 테이블)이 단일 출처이며 Flyway 마이그레이션이 테이블 생성과 초기 행 시드를 함께 수행한다. 강제 업데이트 판단·버전 비교는 클라이언트 책임이고 서버는 판단 재료만 제공한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), springdoc-openapi, Flyway

**Storage**: MySQL — 신규 테이블 `app_version` 단일 행 (Flyway 마이그레이션 + 시드, 스키마 owner = `:api`)

**Testing**: Kotest BehaviorSpec (given/when/then 한국어) + MockMvc 통합 테스트, MySQL Testcontainers

**Target Platform**: `:api` web bootJar (배치·인프라 어댑터 무관)

**Project Type**: 모듈러 모놀리스 백엔드 — 신규 도메인 컨텍스트 `common.domain.appversion` + api 기능 패키지 `com.kbap.api.appversion` + admin 확장

**Performance Goals**: 단일 행 PK 조회 — 별도 목표 불필요 (기존 API 수준)

**Constraints**: 공개 조회는 무인증(JWT 필터 opt-in 방식이라 미등록 = 공개), admin 은 기존 `/api/admin/**` 보호 체계(JWT 필터 + `AdminAuthorizationInterceptor`) 재사용 — WebConfig 변경 없음

**Scale/Scope**: 엔드포인트 3개(공개 GET 1 + admin GET/PUT 2), 엔티티 1, 테이블 1, 마이그레이션 1

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | tasks 단계에서 각 task 를 Red→Green 순으로 구성. 통합 테스트(MockMvc + Testcontainers)·리포지토리 테스트를 구현 전 작성 |
| II. Bounded Contexts | PASS | 신규 컨텍스트 `common.domain.appversion` 은 어떤 도메인도 참조하지 않음(`emptySet`). `ModuleBoundaryTest` 허용 맵에 `"appversion" to emptySet()` 추가 |
| III. Layered Dependency Direction | PASS | `:api` → `:common` 단방향만 사용. 외부 시스템 seam 불필요(인프라 어댑터 무관) |
| IV. Persistence Ownership | PASS | 엔티티·리포지토리는 `common.domain.appversion`(public), `BaseEntity` 상속, JPA 연관관계 없음, 스키마는 api Flyway 소유. 도메인 갱신 로직은 엔티티 도메인 메서드(`update`)가 소유, 트랜잭션 경계는 소비 서비스가 명시 선언 |
| V. Domain Content Language Policy | N/A | 음식 콘텐츠·표시 언어 무관(버전 정보는 언어 중립). 단 "검증은 요청 경계 소유" 조항은 적용 — semver 형식 검증은 admin 요청 DTO 가 소유 |

**Post-Phase-1 재평가**: 위반 없음 — Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-329-app-version-check/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── app-version-api.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/appversion/
├── model/AppVersion.kt              # 엔티티(=도메인 모델, update 도메인 메서드 내장)
└── AppVersionRepository.kt          # Spring Data JPA (public)

api/src/main/kotlin/com/kbap/api/
├── appversion/
│   ├── AppVersionApi.kt             # swagger 문서 인터페이스
│   ├── AppVersionController.kt      # GET /api/app-version (무인증)
│   ├── AppVersionResponse.kt        # minSupportedVersion·latestVersion·storeUrls(ios·aos)
│   └── AppVersionService.kt         # 조회 서비스 (@Transactional(readOnly = true))
└── admin/
    ├── AdminAppVersionApi.kt        # swagger 문서 인터페이스
    ├── AdminAppVersionController.kt # GET·PUT /api/admin/app-version
    ├── AdminAppVersionService.kt    # 갱신 서비스 (@Transactional, dirty checking)
    └── AdminAppVersionUpdateRequest.kt  # semver @Pattern 검증 소유

api/src/main/resources/db/migration/
└── V2026.08.13.**.**.**__app_version_table.sql   # 테이블 생성 + 초기 행 시드 (생성 시각으로 명명)

api/src/test/kotlin/com/kbap/api/
├── appversion/AppVersionIntegrationTest.kt       # 공개 조회 + 무인증 접근 검증
├── admin/AdminAppVersionIntegrationTest.kt       # 관리자 갱신·권한 거부 검증
└── architecture/ModuleBoundaryTest.kt            # 허용 맵에 "appversion" 추가 (기존 파일 수정)
```

**Structure Decision**: 기존 관례 그대로 — 영속은 `common.domain.<ctx>`, 공개 API 는 `com.kbap.api.<feature>` 기능 패키지, 관리자 로직은 `com.kbap.api.admin` 의 `Admin*Service` 로 분리(공용 도메인 서비스 오염 금지). 파일 수가 적으므로 `service`·`dto` 하위 패키지를 만들지 않는다.

## Complexity Tracking

위반 없음 — 해당 없음.

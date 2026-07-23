# Implementation Plan: 프로필 언어 설정 제거 및 메뉴판 스캔 언어 요청 파라미터 전환

**Branch**: `kb-229-scan-lang-param` | **Date**: 2026-07-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-229-scan-lang-param/spec.md`

## Summary

스캔 API(`POST /api/v1/scans`)의 응답 언어를 회원 프로필 저장값(`MemberProfile.appLanguage`, 미설정 시 KO)에서 **`lang` 쿼리 파라미터**(필수, 미지원 코드 → 400 `COMMON-002` 거절 — 전사 en 폴백 정책의 의도적 예외)로 전환하고, 프로필의 마지막 언어 사용처가 사라지므로 **`appLanguage` 를 회원 프로필에서 전면 제거**한다(온보딩 입력·프로필 수정 입력·내 프로필 조회 응답·도메인 모델·JSON 직렬화 모두). 기존 회원 profile JSON 의 `appLanguage` 키는 Flyway 마이그레이션(`JSON_REMOVE`)으로 제거하고, 롤링 배포 중 구 인스턴스가 다시 쓰는 경우에 대비해 `MemberProfileJson` 에 관용 역직렬화도 함께 명시한다(research.md R3).

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), Hibernate `@JdbcTypeCode(SqlTypes.JSON)` + Jackson (profile JSON 컬럼), springdoc-openapi

**Storage**: MySQL — `member.profile` JSON 컬럼(DDL 변경 없음, 폐기 키 제거 데이터 마이그레이션 1건)

**Testing**: Kotest BehaviorSpec(given/when/then 한국어) + JUnit5 플랫폼, 통합 테스트는 MySQL Testcontainers(`@SpringBootTest` + MockMvc)

**Target Platform**: Linux server (bootJar `:app:api`)

**Project Type**: web-service (Gradle 멀티모듈 모듈러 모놀리스)

**Performance Goals**: 기존 스캔 파이프라인 성능 불변 — 언어 결정이 DB 조회(프로필) → 요청 파라미터로 바뀌어 오히려 의존 감소

**Constraints**: lang 필수(비어 있으면 400)·번역 부재 시 ko 폴백은 전사 정책(헌법 원칙 V)을 따르고, 미지원 코드만 스캔 고유로 400 거절한다(Complexity Tracking). 구버전 앱이 보내는 `appLanguage` 값에 온보딩·프로필 수정이 깨지지 않아야 함(무시). 기존 회원 profile JSON 의 legacy `appLanguage` 키에 조회가 깨지지 않아야 함

**Scale/Scope**: API 3면(스캔 요청 계약 변경, 온보딩·프로필 수정 입력 축소, 내 프로필 응답 축소) + 도메인 모델 필드 1개 제거. DB 스키마 변경 0건

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS | 모든 변경을 실패 테스트 선행(Red→Green→Refactor)으로 진행 — tasks 에서 강제 |
| II. Bounded Contexts | PASS | 신규 도메인 간 의존 없음. scan→member 의존은 기존 선언 범위이며, 언어 결정이 프로필에서 분리돼 결합이 **감소**한다(scan 의 member 사용은 존재 확인·기피코드 조회만 남음) |
| III. Layered Dependency Direction | PASS | 의존 방향 변화 없음. 컨트롤러(요청 경계)가 `LanguageCode` 확정 후 도메인 서비스에 전달 |
| IV. Persistence Encapsulation | PASS | 엔티티·리포지토리 경계 불변. `MemberProfileJson` 은 member 도메인 내부에 유지, 스키마 변경 없음 |
| V. Domain Content Language Policy | **DEVIATION (정당화)** | lang 필수(`@field:NotBlank`, 비어 있으면 400)·검증 소유 계층(요청 DTO)·정확 일치 매칭은 준수. **단 미지원 코드 처리가 원칙 V 의 "en 폴백, 언어 코드 값을 사유로 하는 400 은 없다" 와 어긋난다** — 스캔은 400 `COMMON-002` 으로 거절한다. 아래 Complexity Tracking 참조 |

**Post-Phase 1 재평가**: 원칙 V 이탈 1건(정당화됨), 나머지 PASS.

## Project Structure

### Documentation (this feature)

```text
specs/kb-229-scan-lang-param/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── scan-api.md      # Phase 1 output — 스캔 API 계약 변경 + member API 계약 축소
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
app/api/src/main/kotlin/com/kbap/app/api/
├── scan/
│   ├── ScanController.kt        # 수정 —  ScanLangRequest 추가, 지원 코드만 LanguageCode 로 확정(미지원 400)
│   ├── ScanLangRequest.kt       # 신규 — lang 쿼리 파라미터 DTO(@field:NotBlank, HomeRequest 패턴)
│   └── ScanApi.kt               # 수정 — swagger 문서(lang 파라미터 명세)
└── member/
    ├── OnboardingRequest.kt     # 수정 — appLanguage 제거
    ├── ProfileUpdateRequest.kt  # 수정 — appLanguage 제거
    ├── MyProfileResponse.kt     # 수정 — appLanguage 제거
    └── MemberApi.kt             # 수정 — swagger 예시에서 appLanguage 제거

domain/scan/src/main/kotlin/com/kbap/domain/scan/
└── ScanService.kt               # 수정 — lang 파라미터 수신, 프로필 appLanguage 조회 제거

domain/member/src/main/kotlin/com/kbap/domain/member/
├── MemberService.kt             # 수정 — 온보딩·수정 전달 인자에서 appLanguage 제거
├── dto/MemberProfileInput.kt    # 수정 — appLanguage 제거
├── dto/ProfileUpdateInput.kt    # 수정 — appLanguage 제거
├── dto/MyProfileResult.kt       # 수정 — appLanguage 제거
└── model/
    ├── Member.kt                # 수정 — completeOnboarding·updateProfile 파라미터에서 appLanguage 제거
    ├── MemberProfile.kt         # 수정 — appLanguage 필드·validatedLanguage 제거
    └── MemberProfileJson.kt     # 수정 — appLanguage 제거 + unknown key 관용 역직렬화(R3)

테스트(미러 구조):
├── app/api/src/test/.../scan/ScanControllerTest.kt      # lang 시나리오(지정 언어·미지원 400 COMMON-002·누락 400)
├── app/api/src/test/.../member/MemberControllerTest.kt  # appLanguage 없는 온보딩·수정·조회 + 구버전 값 무시
├── domain/member/src/test/.../model/{MemberProfileTest,MemberTest}.kt  # 필드 제거 반영 + legacy JSON 관용
└── app/api/src/test/.../{home/HomeTestSeed,food/FoodTestSeed,auth/AuthControllerTest,scenario/ScenarioApiDriver}.kt  # 시드·드라이버에서 appLanguage 정리
```

**Structure Decision**: 기존 멀티모듈 구조 그대로 — 신규 파일은 `ScanLangRequest.kt` 1개뿐이고 나머지는 전부 기존 파일 수정. 모듈 경계·의존 선언 변경 없음.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 원칙 V 이탈 — 스캔은 미지원 `lang` 코드를 en 으로 폴백하지 않고 400 `COMMON-002` 으로 거절한다 | 스캔 응답은 사용자가 **먹을 것을 고르는 화면**이라, 요청과 다른 언어의 메뉴명이 조용히 내려가면 사용자가 알아채지 못한 채 잘못된 표기를 보고 판단한다. ADR-0013 이 en 폴백의 근거로 든 "진입 화면이 안 열린다" 문제는 스캔에 해당하지 않는다 — 스캔은 사용자가 명시적으로 실행하는 후속 동작이라 400 을 받아도 앱이 잠기지 않고 클라이언트가 재시도·기본 언어 지정으로 복구할 수 있다 | en 폴백(전사 정책 준수)은 클라이언트의 코드 오타(`jp`)·대소문자 오류(`EN`)가 200 으로 조용히 나가 QA 에서 드러나지 않는다 — ADR-0013 이 "감수하는 비용" 으로 명시한 그 위험이며, 스캔에서는 감수 대상이 아니라고 판단했다 |

> 이 이탈은 [ADR-0014](../../docs/adr/0014-scan-lang-param-strict-rejection.md) 로 기록했다(ADR-0013 을 supersede 하지 않는 범위 한정 예외). 헌법 원칙 V 는 개정하지 않았다 — 확대 여부는 운영 데이터를 보고 판단한다.

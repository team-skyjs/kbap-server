# Implementation Plan: 회원 통화 설정 — 국가 기반 자동 지정 및 프로필에서 개별 변경

**Branch**: `kb-322-member-currency` | **Date**: 2026-08-11 | **Spec**: [spec.md](./spec.md)

## Summary

회원 프로필에 **통화**를 추가한다. 온보딩에서 국가가 확정될 때 그 국가의 통화를 자동으로 저장하고, 이후에는 프로필 수정으로 통화만 따로 바꾼다. 국가를 바꿔도 통화는 따라가지 않는다(FR-007).

기술적으로는 **식별자 enum + 매핑 + nullable 컬럼 + 백필** 조합이다. `CurrencyCode` enum 을 공유 vocabulary 로 두고, `CountryCode` enum 에 `currency` 필드를 얹어 197개 전수 매핑을 컴파일러가 강제하게 한다. 기존 회원은 마이그레이션에서 국가 기준으로 백필한다.

환율·금액 환산은 **범위 밖**이다 — 후속 KB-323 이 이 값을 읽는다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web/validation/data-jpa), Flyway

**Storage**: MySQL. `member` 테이블에 `currency varchar(3) NULL` 추가 + 백필 마이그레이션 1건.

**Testing**: Kotest `BehaviorSpec`. 단위 = `:common` 도메인(`MemberProfileTest`), 통합 = `:api` MockMvc + MySQL Testcontainers, 정합 = 시드-동기화 테스트.

**Target Platform**: Linux 서버(`:api` bootJar), 운영 인스턴스 2대

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 — `:common`(도메인·영속) + `:api`(HTTP 경계)

**Performance Goals**: 프로필 조회·수정 응답 시간에 유의미한 변화 없음(컬럼 1개 추가, 조인 없음).

**Constraints**: 기존 클라이언트 무수정 동작(요청 필드 추가는 선택, 응답 필드 추가는 additive). 블루/그린 배포 중 구 리비전이 신 스키마 위에서 도는 구간을 견딜 것.

**Scale/Scope**: 프로덕션 변경 6파일 + 마이그레이션 1개. 신규 파일 1개(`CurrencyCode`). `CountryCode` 197줄 일괄 수정.

## Constitution Check

| 원칙 | 판정 | 근거 |
|------|------|------|
| **I. Test-First (NON-NEGOTIABLE)** | ✅ PASS | 전 스토리가 실패 테스트 선행. 검증(`validatedCurrency`)·정책(온보딩 자동 지정, 국가 변경 시 불변)·백필 정합 모두 결정적이라 Red 를 만들 수 있다. |
| **II. Bounded Contexts** | ✅ PASS | `CurrencyCode` 를 `com.kbap.common.domain` 루트(공유 vocabulary)에 둔다 — 후속 KB-323 에서 scan 이 참조해도 도메인 간 의존이 생기지 않는다(research R2). `ModuleBoundaryTest` 허용 맵 수정 불필요. |
| **III. Layered Dependency Direction** | ✅ PASS | 신규 seam·모듈 의존 없음. `:api` 는 요청/응답 DTO 만, 정책은 `:common` 도메인이 소유. |
| **IV. Persistence Ownership** | ✅ PASS | 엔티티가 곧 도메인 모델 — `Member.completeOnboarding` 전이 안에서 통화가 결정된다(research R7). 컬럼 추가는 Flyway(owner=api). 트랜잭션 경계는 기존 `MemberService` 그대로. JPA 연관 없음(값 컬럼). |
| **V. Domain Content Language Policy** | ✅ PASS | 통화 코드는 번역 대상 콘텐츠가 아니다 — 표시용 기호·명칭은 클라이언트 소유. 다만 **"정확 일치·관대한 정규화 금지"** 태도는 그대로 따른다(`lang` 취급과 동일). 고정 reference taxonomy 의 식별자 enum 패턴을 적용한다. |
| **Additional Constraints** | ✅ PASS | 외부 호출 없음. 도메인 모델을 응답으로 직접 노출하지 않음(`MyProfileResponse` 경유). |

**게이트 결과**: 위반 없음. Complexity Tracking 비움.

**Phase 1 재점검**: 설계 산출물 확정 후 재평가 — 신규 모듈·seam·의존이 생기지 않아 판정 불변. ✅ PASS 유지.

## Project Structure

### Documentation (this feature)

```text
specs/kb-322-member-currency/
├── plan.md · spec.md · research.md · data-model.md · quickstart.md
├── contracts/
│   └── member-currency-api.md    # 온보딩·프로필 수정·프로필 조회 계약
└── checklists/requirements.md
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/
├── CurrencyCode.kt                       # [신규] 공유 vocabulary — LanguageCode 옆
└── member/model/
    ├── CountryCode.kt                    # [수정] 197개 전부에 currency 필드 부여
    ├── MemberProfile.kt                  # [수정] currency 필드 + updatedWith + validatedCurrency
    └── Member.kt                         # [수정] currency 컬럼, completeOnboarding 자동 지정, updateProfile 전달

common/src/main/kotlin/com/kbap/common/core/error/
└── ErrorCode.kt                          # [수정] INVALID_CURRENCY_CODE("MEMBER-010", 400)

common/src/main/kotlin/com/kbap/common/domain/member/dto/
├── ProfileUpdateInput.kt                 # [수정] currency 추가
└── MyProfileResult.kt                    # [수정] currency 추가

api/src/main/kotlin/com/kbap/api/member/
├── ProfileUpdateRequest.kt               # [수정] currency 선택 필드
├── MyProfileResponse.kt                  # [수정] currency 응답 필드
└── MemberApi.kt                          # [수정] swagger 문서

api/src/main/resources/db/migration/
└── V2026.08.11.HH.mm.ss__member_currency.sql   # [신규] 컬럼 추가 + 국가 기준 백필
```

**Structure Decision**: 신규 파일은 `CurrencyCode` 하나뿐이다. 나머지는 기존 프로필 필드(`countryCode`·`profileImageUrl`)가 지나가는 경로에 필드를 하나 더 얹는 것이라, 같은 파일들을 그대로 따라간다. `OnboardingRequest` 는 **건드리지 않는다** — 통화가 요청 필드가 아니어야 FR-003(보내도 무시)이 구조적으로 성립한다.

## 구현 순서

1. **[Red]** `CurrencyCode` enum + `CountryCode.currency` 매핑 — 전수 매핑 테스트(모든 국가가 통화를 갖는다)
2. **[Green]** 197개 매핑 채움
3. **[Red→Green]** `ErrorCode.INVALID_CURRENCY_CODE`(MEMBER-010) + `MemberProfile.updatedWith(currency)` + `validatedCurrency` — 유효/무효/미전송 유지 3케이스
4. **[Red→Green]** `Member.completeOnboarding` 자동 지정 + `updateProfile` 전달 — 온보딩 후 통화 존재, 국가 변경 시 통화 불변
5. **[Red→Green]** DTO·응답 배선(`ProfileUpdateInput`·`MyProfileResult`·`ProfileUpdateRequest`·`MyProfileResponse`) + MockMvc 시나리오
6. **[Red→Green]** 마이그레이션(컬럼+백필) + 시드-동기화 테스트(SQL ↔ enum 정합)
7. **[검증]** `./gradlew build` 전체 통과

## Complexity Tracking

> Constitution Check 위반 없음 — 비움.

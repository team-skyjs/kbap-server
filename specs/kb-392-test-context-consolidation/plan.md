# Implementation Plan: 테스트 Spring 컨텍스트 통합

**Branch**: `kb-392-test-context-consolidation` | **Date**: 2026-08-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-392-test-context-consolidation/spec.md` · Jira [KB-392](https://simhani1.atlassian.net/browse/KB-392)

## Summary

통합 테스트의 설정 조합(8+7+3 종)을 모듈별 합성 애너테이션 하나(`@IntegrationTest`·`@BatchIntegrationTest`)와 단일 부트 클래스(`CommonTestApp`)로 모아 JVM 당 컨텍스트를 api 2 · common 1 · batch 1 로 줄인다. 소셜 인증 페이크 두 개를 "토큰 = 식별자" 인 프로그래머블 페이크 하나로 통일하고, Hibernate 통계 프로퍼티는 테스트 yml 전역으로 올린다. 테스트 본문은 바꾸지 않는다 — 헤더 치환·페이크 참조·(드러난 경우만) 정리 코드.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21

**Primary Dependencies**: Spring Boot 4.1 test(`@SpringBootTest`·`@AutoConfigureMockMvc`·`@Import` 메타 애너테이션), Kotest 5.9 + `kotest-extensions-spring`, Testcontainers(기존 픽스처 그대로)

**Storage**: 변경 없음(테스트 MySQL/Redis 컨테이너)

**Testing**: `./gradlew clean build` ×2, `docker ps` 로 컨텍스트 수 측정

**Target Platform**: 로컬 Docker Desktop · GitHub Actions ubuntu-latest

**Project Type**: 테스트 인프라 정리 — 프로덕션 코드 무변경

**Performance Goals**: 컨텍스트 api 8→2, common 7→1, batch 3→1; Flyway 실행 api 8→2

**Constraints**: 테스트 본문 diff 0(FR-009), Kotlin 주석 금지, `@SpringBootTest` 를 클래스에 직접 쓰는 곳은 ecs 로깅 1개만

**Scale/Scope**: api 76 헤더 + common 11 헤더 + batch 6 헤더 = 93 파일 기계 치환, 신규 4파일(애너테이션 2·CommonTestApp·SlowJobTestConfig 분리), 삭제 8파일(`*TestApp` 7 + 시나리오 페이크 1)

## Constitution Check

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | Red = 대표 클래스 2개(MockMvc 有/無)의 헤더를 존재하지 않는 `@IntegrationTest` 로 바꿔 컴파일 실패 → 애너테이션 신설로 Green + 두 클래스가 컨테이너 1개를 공유함을 확인 → 나머지 74개 치환. 페이크 통일은 시나리오 4 + 인증/회원 3 이 회귀 테스트. |
| II. Bounded Contexts | 해당 없음 | 프로덕션 코드 무변경 |
| III. Dependency Direction | PASS | 테스트 소스셋 내부 이동뿐. 합성 애너테이션은 모듈 test 소스에 두어 common testFixtures 가 api 페이크를 알지 않는다. |
| IV. Persistence Ownership | 해당 없음 | 엔티티·리포지토리·Flyway 무변경 |
| V. Language Policy | 해당 없음 | |

Post-design re-check: 위반 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-392-test-context-consolidation/
├── spec.md · plan.md · research.md · data-model.md · quickstart.md
├── checklists/requirements.md
└── tasks.md
```

### Source Code (repository root)

```text
api/src/test/kotlin/com/kbap/api/
├── IntegrationTest.kt                         # 신규 합성 애너테이션
├── auth/FakeSocialTokenVerifierConfig.kt      # 신규(AuthControllerTest.kt 바닥에서 이동) — 토큰=식별자 페이크 + 삭제기 + @TestConfiguration
├── auth/AuthControllerTest.kt                 # 페이크 정의 제거, 헤더 치환, login 기본 토큰 DEFAULT_SUB
├── member/MemberControllerTest.kt, member/MemberProfileUpdateVersionTest.kt   # 헤더·기본 토큰
├── scenario/ScenarioSocialTokenVerifierConfig.kt   # 삭제
├── food/FoodServiceTest.kt                    # properties 제거 → @IntegrationTest
├── core/logging/StructuredConsoleLoggingTest.kt    # 유지(유일한 예외)
└── (그 외 71개)                                # 3줄 헤더 → @IntegrationTest
api/src/test/resources/application.yml         # spring.jpa.properties.hibernate.generate_statistics: true
common/src/test/kotlin/com/kbap/common/
├── CommonTestApp.kt                           # 신규
└── domain/*/… 11개                            # @SpringBootTest + @Import(MySql) 로 통일, *TestApp 7개 삭제
batch/src/test/kotlin/com/kbap/batch/
├── BatchIntegrationTest.kt                    # 신규 합성 애너테이션
├── trigger/SlowJobTestConfig.kt               # 신규(BatchJobTriggerControllerTest.kt 바닥에서 이동)
└── 6개                                        # 헤더 → @BatchIntegrationTest
CLAUDE.md · ../kbap-agenthub/wiki/test-context-consolidation.md + INDEX.md
```

**Structure Decision**: 합성 애너테이션은 모듈 루트 패키지(`com.kbap.api`·`com.kbap.batch`)에 하나씩. 페이크·픽스처 이동은 같은 패키지 안에서만(import 변화 최소).

## 설계 상세

### `@IntegrationTest` (api)

```kotlin
package com.kbap.api

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import(
    MySqlContainerConfig::class,
    RedisContainerConfig::class,
    FakeSocialTokenVerifierConfig::class,
    FakePlaceSearchConfig::class,
)
annotation class IntegrationTest
```

치환 규칙(76개): 파일 상단의 `@SpringBootTest…`·`@AutoConfigureMockMvc`·`@Import(…)` 줄을 지우고 `@IntegrationTest` 한 줄. `@Tags("scenario")` 등 Kotest 애너테이션은 그대로. import 정리: `SpringBootTest`·`AutoConfigureMockMvc`·`Import`·`MySqlContainerConfig`·`RedisContainerConfig`·`ScenarioSocialTokenVerifierConfig`·`FakeSocialTokenVerifierConfig`·`FakePlaceSearchConfig` 가 헤더 외에 쓰이지 않으면 제거(Kotlin 은 미사용 import 가 경고라 컴파일은 되지만 정리한다). 스크립트로 일괄 처리 후 `git diff --stat` 로 파일당 변경이 헤더·import 뿐인지 확인.

### 통일 페이크 (`api/auth/FakeSocialTokenVerifierConfig.kt`)

```kotlin
class FakeSocialTokenVerifier : SocialTokenVerifier {
    private var failure: ErrorCode? = null

    override fun verify(idToken: String): SocialIdentity {
        failure?.let { throw BusinessException(it) }
        return SocialIdentity(SocialProvider.GOOGLE, idToken, DEFAULT_EMAIL)
    }

    fun failWith(errorCode: ErrorCode) { failure = errorCode }
    fun reset() { failure = null }

    companion object {
        const val DEFAULT_SUB: String = "google-sub-fixed"
        const val DEFAULT_EMAIL: String = "user@gmail.com"
    }
}
```

`FakeSocialAccountDeleter`·`@TestConfiguration FakeSocialTokenVerifierConfig`(둘 다 `@Primary`) 는 현재 코드 그대로 이동. 인증/회원 테스트 3개의 `login(idToken: String = "valid-token")` 류 기본값을 `FakeSocialTokenVerifier.DEFAULT_SUB` 로(본문의 `"valid-token"` 리터럴 사용처는 해당 파일에서 grep 해 같은 상수로).

### `CommonTestApp`

```kotlin
package com.kbap.common

@SpringBootConfiguration
@EnableAutoConfiguration
@AutoConfigurationPackage(basePackages = ["com.kbap.common.domain"])
class CommonTestApp
```

11개 헤더: `@SpringBootTest` / `@Import(MySqlContainerConfig::class)`. `classes = [...]` 제거.

### `@BatchIntegrationTest`

```kotlin
package com.kbap.batch

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class, SlowJobTestConfig::class)
annotation class BatchIntegrationTest
```

### 검증

quickstart 참조 — `docker ps` 컨테이너 수(api ≤ 2, common 1, batch 1), `clean build` 2회, `git diff --stat` 로 본문 무변경.

## Complexity Tracking

없음.

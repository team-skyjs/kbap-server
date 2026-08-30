# Implementation Plan: 스캔 rate-limit 을 "인식 실패"와 분리

**Branch**: `kb-394-scan-rate-limit` (worktree) | **Date**: 2026-08-30 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-394-scan-rate-limit/spec.md` · Jira [KB-394](https://simhani1.atlassian.net/browse/KB-394)

## Summary

비전 호출은 Spring AI 2.0 → **openai-java SDK** 경로라 벤더 오류가 SDK 원 예외(`RateLimitException`·`InternalServerException`·`OpenAIIoException`)로 도착하는데, 어댑터는 Spring AI RestClient 시절의 `TransientAiException` 만 잡고 있어(죽은 코드) 429 를 포함한 모든 벤더 오류가 SCAN-002 "인식 실패"로 나간다. 어댑터의 예외 매핑을 SDK 타입으로 바로잡고, 429 는 새 포트 예외 `MenuBoardVisionRateLimitedException` → **SCAN-008**(503, "일시적으로 요청이 많습니다…") 로 분리한다. SDK 의 무제한 대기 재시도(`Retry-After` ×2)는 끄고 어댑터가 **예산(기본 10s) 안에서만** 재시도한다 — `x-should-retry=false`(비일시적) 는 즉시 실패. 로그는 `rate-limit` 마커와 갈래·`Retry-After` 를 남기고, 응답 payload 에 `retryAfterSeconds` 를 싣는다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21

**Primary Dependencies**: Spring AI 2.0 `spring-ai-openai`(내부 openai-java-core 4.39 SDK — `com.openai.errors.*`, `com.openai.core.http.Headers`), Spring Boot 4.1, Kotest

**Storage**: 변경 없음

**Testing**: 어댑터 Spring-free 단위 테스트 + `ScanControllerTest` 통합(`@IntegrationTest`, `FakeMenuBoardVisionExtractor`)

**Target Platform**: api 모듈(스캔 v1·v2 공통 서비스 경로)

**Project Type**: 백엔드 오류 매핑·재시도 방침

**Performance Goals**: 비일시적 429 → 벤더 응답 + <1s; 일시적 429 총 대기 ≤ 예산(10s) + 1회 호출

**Constraints**: `common.port.llm` 은 SDK 타입을 몰라야 함(ArchUnit — 포트 순수). Kotlin 주석 금지. 다른 실패 경로 응답 불변(FR-008).

**Scale/Scope**: 재시도 규칙 = OpenAI 공식 처방(Retry-After 우선·지수+지터·횟수와 총시간 제한·quota/billing 은 재시도 금지). 프로덕션 5파일(ErrorCode·포트 예외·어댑터·ScanService·LlmModelProperties/LlmConfiguration) + API 문서 2파일, 테스트 3파일(어댑터·컨트롤러·OpenAPI 스냅샷) + 페이크 1

## Constitution Check

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | 포트 예외·에러코드 부재로 컴파일 Red → 어댑터 테스트(SDK 예외 빌더로 구성) Red → 루프 구현 Green → 컨트롤러 테스트 Red/Green. |
| II. Bounded Contexts | PASS | 스캔 컨텍스트 내부. 포트 예외는 `common.port.llm`(기존 `MenuBoardVisionUnavailableException` 옆). |
| III. Dependency Direction | PASS | SDK 타입은 `common.infra.llm` 어댑터에서만 참조. 포트는 순수(`retryAfterSeconds: Long?`·`exhausted: Boolean`). ScanService 는 포트 예외만 안다. |
| IV. Persistence Ownership | 해당 없음 | |
| V. Language Policy | 해당 없음 | |
| Additional — 외부 호출을 트랜잭션 밖에서 | PASS | `ScanService.scan` 은 비전 호출을 트랜잭션 없이 수행(현행). 재시도 sleep 도 트랜잭션 밖. |

Post-design re-check: 위반 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-394-scan-rate-limit/
├── spec.md · plan.md · research.md · data-model.md · quickstart.md
├── contracts/scan-rate-limit-error.md
└── checklists/requirements.md · tasks.md(다음 단계)
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/
├── core/error/ErrorCode.kt                              # + SCAN_RATE_LIMITED("SCAN-008", 503, …)
├── port/llm/MenuBoardVisionExtractor.kt                 # + class MenuBoardVisionRateLimitedException(retryAfterSeconds: Long?, exhausted: Boolean)
├── infra/llm/menu/OpenAiMenuBoardVisionExtractor.kt     # SDK 예외 매핑 + 예산 재시도 루프 + sleep 주입
├── infra/llm/config/LlmModelProperties.kt               # VisionProps.retryBudget: Duration = 10s, maxRetries 기본 0
└── infra/llm/config/LlmConfiguration.kt                 # retryBudget 전달, maxRetries=0
api/src/main/kotlin/com/kbap/api/
├── scan/ScanService.kt                                  # catch RateLimited → warn(rate-limit 마커) + SCAN-008(payload retryAfterSeconds)
├── scan/ScanApi.kt · scan/ScanV2Api.kt                  # 503 설명·errorCodes 에 SCAN-008
common/src/test/kotlin/com/kbap/common/infra/llm/menu/OpenAiMenuBoardVisionExtractorTest.kt   # 예외 매핑·예산 테스트
api/src/test/kotlin/com/kbap/api/
├── scan/FakeMenuBoardVisionExtractor.kt                 # + rateLimitedOn(path, retryAfterSeconds)
├── scan/ScanControllerTest.kt                           # SCAN-008 v1/v2·횟수·payload 케이스
└── openapi/OpenApiSnapshotTest.kt                       # 스캔 설명에 SCAN-008
../kbap-agenthub/wiki/scan-credit-limit-design.md        # SDK 예외 기준으로 정정 + SCAN-008·재시도 예산
```

**Structure Decision**: 새 파일 없음 — 포트 예외는 기존 포트 파일에, 재시도는 어댑터 안에. 설정은 기존 `VisionProps` 확장.

## 설계 상세

### 포트 (`common.port.llm`)

```kotlin
class MenuBoardVisionRateLimitedException(
    val retryAfterSeconds: Long?,
    val exhausted: Boolean,
    limits: String,
    cause: Throwable,
) : RuntimeException(limits, cause)

class MenuBoardVisionQuotaExhaustedException(val code: String, cause: Throwable) : RuntimeException(code, cause)
```

### 어댑터 (`OpenAiMenuBoardVisionExtractor`)

```kotlin
class OpenAiMenuBoardVisionExtractor(
    …,
    private val retryBudget: Duration = Duration.ofSeconds(10),
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
)

private fun callWithRetryBudget(prompt: Prompt): ChatResponse {
    val deadline = Instant.now().plus(retryBudget)
    var attempt = 0
    while (true) {
        try {
            return chatModel.call(prompt)
        } catch (e: RateLimitException) {
            val code = e.code().orElse("")
            if (code in QUOTA_CODES) throw MenuBoardVisionQuotaExhaustedException(code, e)
            val retryAfter = retryAfterOf(e.headers())
            val limits = limitsOf(e.headers())
            if (e.headers().values("x-should-retry").firstOrNull() == "false") throw MenuBoardVisionRateLimitedException(retryAfter?.seconds, exhausted = false, limits, e)
            if (!waitWithinBudget(retryAfter, attempt++, deadline)) throw MenuBoardVisionRateLimitedException(retryAfter?.seconds, exhausted = true, limits, e)
        } catch (e: InternalServerException) {
            if (!waitWithinBudget(retryAfterOf(e.headers()), attempt++, deadline)) throw MenuBoardVisionUnavailableException(e)
        } catch (e: OpenAIIoException) {
            if (!waitWithinBudget(null, attempt++, deadline)) throw MenuBoardVisionUnavailableException(e)
        }
    }
}

private fun waitWithinBudget(retryAfter: Duration?, attempt: Int, deadline: Instant): Boolean {
    val wait = retryAfter ?: backoffWithJitter(attempt)
    if (Instant.now().plus(wait).isAfter(deadline)) return false
    sleep(wait)
    return true
}

private fun backoffWithJitter(attempt: Int): Duration =
    Duration.ofMillis(((500L shl attempt) * Random.nextDouble(0.75, 1.25)).toLong())

private fun limitsOf(headers: Headers): String =
    listOf("limit-requests", "limit-tokens", "remaining-requests", "remaining-tokens", "reset-requests", "reset-tokens", "remaining-project-tokens", "reset-project-tokens")
        .mapNotNull { key -> headers.values("x-ratelimit-$key").firstOrNull()?.let { "$key=$it" } }
        .joinToString(" ")

companion object {
    private val QUOTA_CODES = setOf("insufficient_quota", "credit_balance_exhausted", "organization_spend_limit_exceeded", "project_spend_limit_exceeded", "organization_usage_limit_exceeded")
}

private fun retryAfterOf(headers: Headers): Duration? =
    headers.values("retry-after-ms").firstOrNull()?.toLongOrNull()?.let(Duration::ofMillis)
        ?: headers.values("retry-after").firstOrNull()?.toLongOrNull()?.let(Duration::ofSeconds)
```

- `extract()` 의 기존 `try { chatModel.call } catch (TransientAiException) … catch (ResourceAccessException)` 을 `callWithRetryBudget(prompt)` 호출로 교체. `BadRequestException` 등 4xx 는 전파(→ SCAN-002 유지).
- 남은 한도 헤더는 `MenuBoardVisionRateLimitedException` 메시지로: `"rate-limit remaining-requests=… remaining-tokens=…"` (cause 체인 로그에 남음).

### 설정

`VisionProps`: `maxRetries: Int = 0`(SDK 재시도 off), `retryBudget: Duration = Duration.ofSeconds(10)`. `LlmConfiguration` 이 어댑터에 `retryBudget` 전달, `visionChatOptions` 는 `maxRetries` 항상 설정.

### ScanService

```kotlin
} catch (e: MenuBoardVisionRateLimitedException) {
    log.warn("메뉴판 비전 rate-limit — kind={} retryAfterSeconds={} limits={} imagePath={}", if (e.exhausted) "EXHAUSTED" else "IMMEDIATE", e.retryAfterSeconds, e.message, imagePath, e)
    throw BusinessException(ErrorCode.SCAN_RATE_LIMITED, payload = e.retryAfterSeconds?.let { mapOf("retryAfterSeconds" to it) })
} catch (e: MenuBoardVisionQuotaExhaustedException) {
    log.error("메뉴판 비전 quota 소진 — code={} imagePath={}", e.code, imagePath, e)
    throw BusinessException(ErrorCode.SCAN_VISION_UNAVAILABLE)
} catch (e: MenuBoardVisionUnavailableException) { … 기존 … }
```

`MenuBoardVisionRateLimitedException` 이 `MenuBoardVisionUnavailableException` 의 하위가 아니므로 catch 순서 무관.

### 계약

[contracts/scan-rate-limit-error.md](contracts/scan-rate-limit-error.md) — 503 `SCAN-008`, payload `{ "retryAfterSeconds": 20 }` 또는 null.

## Complexity Tracking

없음 — 재시도 루프(~20줄)는 스펙 FR-005 의 총 대기 상한이 SDK 옵션으로 표현 불가능해서 필요(research R-3).

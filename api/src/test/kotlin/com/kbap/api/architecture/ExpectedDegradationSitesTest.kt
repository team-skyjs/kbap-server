package com.kbap.api.architecture

import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.io.File

@Tags("arch")
class ExpectedDegradationSitesTest : BehaviorSpec({

    val marked = Regex("""expected\s*=\s*true|BusinessException\([^)]*,\s*true\s*\)""")

    given("예상된 저하 표시(BusinessException 의 expected)") {
        `when`("운영 코드를 전수 검사하면") {
            then("번역 동시 상한 초과 한 곳뿐이다 — 다른 5xx 가 조용해지지 않는다") {
                val sites = listOf("src/main/kotlin", "../common/src/main/kotlin", "../batch/src/main/kotlin")
                    .flatMap { root -> File(root).walkTopDown().filter { it.extension == "kt" }.toList() }
                    .flatMap { file -> file.readLines().filter { marked.containsMatchIn(it) }.map { file.name } }

                withClue(
                    "expected 로 표시한 5xx 는 Sentry 이벤트를 보내지 않고 WARN 으로만 남는다. " +
                        "부하 때 반복되는 '우리가 일부러 건 거절'에만 쓴다 — 외부 장애·용량 신호(스캔 몰림 등)에 붙이면 장애가 조용해진다. " +
                        "새로 붙일 때는 이 목록에 더하고 이유를 PR 에 적는다.",
                ) {
                    sites shouldBe listOf("TranslationService.kt")
                }
            }
        }
    }
})

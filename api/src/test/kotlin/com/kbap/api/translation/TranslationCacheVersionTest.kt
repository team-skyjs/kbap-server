package com.kbap.api.translation

import com.kbap.common.domain.LanguageCode
import com.kbap.common.infra.llm.translation.TranslationPrompt
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.security.MessageDigest

class TranslationCacheVersionTest : BehaviorSpec({

    fun fingerprintOf(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)

    given("번역 프롬프트와 캐시 판(CACHE_VERSION)") {
        `when`("프롬프트 지문과 캐시 판을 나란히 보면") {
            then("고정해 둔 한 쌍과 같다(전 언어의 프롬프트를 이어 붙인 지문) — 프롬프트를 고치면 여기서 멈춰 저장된 번역을 다시 번역할지 정한다") {
                withClue(
                    "번역 프롬프트가 바뀌었다. 저장된 번역은 옛 프롬프트로 만든 것이다. " +
                        "① 옛 번역을 다시 번역해야 하면(품질·형식이 달라지는 수정) TranslationService.CACHE_VERSION 을 올리고 " +
                        "② 그대로 써도 되면(오탈자 등) 판은 두고, 어느 쪽이든 이 테스트의 한 쌍을 새 값으로 고친다.",
                ) {
                    val prompts = LanguageCode.entries.joinToString("\n\n") { TranslationPrompt.system(it, "LANG-fingerprint") }

                    (fingerprintOf(prompts) to TranslationService.CACHE_VERSION) shouldBe ("19ccc8b58351cccd" to "3")
                }
            }
        }
    }
})

package com.kbap.common.domain.notification

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.notification.model.NotificationType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

class PushMessageRendererTest : BehaviorSpec({
    val renderer = PushMessageRenderer()
    val args = mapOf("food" to "김치찌개", "title" to "t", "body" to "b")

    given("푸시 템플릿 렌더러") {
        `when`("전 유형 × 전 로케일을 렌더하면") {
            then("제목·본문이 비어 있지 않고 길이 상한 안에 있다") {
                NotificationType.entries.forEach { type ->
                    LanguageCode.entries.forEach { lang ->
                        val content = renderer.render(type, lang, args, marketing = false)
                        content.title.shouldNotBeBlank()
                        content.body.shouldNotBeBlank()
                        content.title.length shouldBeLessThanOrEqual 200
                        content.body.length shouldBeLessThanOrEqual 1000
                    }
                }
            }

            then("수신거부 안내 문구도 전 로케일에 있다") {
                LanguageCode.entries.forEach { lang -> PushTemplates.optOutNotice.getValue(lang).shouldNotBeBlank() }
            }
        }

        `when`("치환 키를 넘기면") {
            then("{food} 가 값으로 바뀐다") {
                val content = renderer.render(NotificationType.HELPFUL, LanguageCode.KO, args, marketing = false)
                content.body shouldContain "김치찌개"
                content.body shouldNotContain "{food}"
            }

            then("주지 않은 키는 빈 문자열로 치환된다") {
                val content = renderer.render(NotificationType.REVIEW_REMINDER, LanguageCode.KO, emptyMap(), marketing = false)
                content.title shouldNotContain "{"
                content.body shouldNotContain "{"
            }
        }

        `when`("광고성으로 렌더하면") {
            then("제목 앞 (광고) 와 본문 끝 수신거부 안내가 붙는다") {
                val content = renderer.render(NotificationType.SCAN_SUGGESTION, LanguageCode.EN, args, marketing = true)
                content.title shouldStartWith "(광고) "
                content.body shouldEndWith PushTemplates.optOutNotice.getValue(LanguageCode.EN)
            }
        }

        `when`("정보성으로 렌더하면") {
            then("광고 표기와 안내가 없다") {
                val content = renderer.render(NotificationType.HELPFUL, LanguageCode.KO, args, marketing = false)
                content.title shouldNotContain "(광고)"
                content.body shouldNotContain PushTemplates.optOutNotice.getValue(LanguageCode.KO)
            }
        }

        `when`("NOTICE 를 렌더하면") {
            then("title·body 인자가 그대로 나온다") {
                val content = renderer.render(
                    NotificationType.NOTICE,
                    LanguageCode.JA,
                    mapOf("title" to "K-Bap", "body" to "테스트 알림입니다."),
                    marketing = false,
                )
                content.title shouldBe "K-Bap"
                content.body shouldBe "테스트 알림입니다."
            }

            then("긴 본문은 1000자로 절단된다") {
                val content = renderer.render(
                    NotificationType.NOTICE,
                    LanguageCode.KO,
                    mapOf("title" to "t", "body" to "x".repeat(1200)),
                    marketing = false,
                )
                content.body.length shouldBe 1000
            }
        }

        `when`("기본 광고성 여부를 보면") {
            then("SCAN_SUGGESTION 만 true 다") {
                NotificationType.entries.filter { it.marketingByDefault } shouldBe listOf(NotificationType.SCAN_SUGGESTION)
            }
        }
    }
})

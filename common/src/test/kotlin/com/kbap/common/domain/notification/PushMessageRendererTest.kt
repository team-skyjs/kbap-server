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
                        val content = renderer.render(type, lang, args)
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
                val content = renderer.render(NotificationType.HELPFUL, LanguageCode.KO, args)
                content.body shouldContain "김치찌개"
                content.body shouldNotContain "{food}"
            }

            then("주지 않은 키는 빈 문자열로 치환된다") {
                val content = renderer.render(NotificationType.REVIEW_REMINDER, LanguageCode.KO, emptyMap())
                content.title shouldNotContain "{"
                content.body shouldNotContain "{"
            }
        }

        `when`("광고성 유형을 렌더하면") {
            then("제목 앞 (광고) 와 본문 끝 수신거부 안내가 붙는다") {
                val content = renderer.render(NotificationType.SCAN_SUGGESTION, LanguageCode.EN, args)
                content.title shouldStartWith "(광고) "
                content.body shouldEndWith PushTemplates.optOutNotice.getValue(LanguageCode.EN)
            }
        }

        `when`("정보성 유형을 렌더하면") {
            then("광고 표기와 안내가 없다") {
                val content = renderer.render(NotificationType.HELPFUL, LanguageCode.KO, args)
                content.title shouldNotContain "(광고)"
                content.body shouldNotContain PushTemplates.optOutNotice.getValue(LanguageCode.KO)
            }
        }

        `when`("NEWS 를 렌더하면") {
            then("title·body 인자가 통과하고 광고 표기·수신거부 안내가 붙는다") {
                val content = renderer.render(
                    NotificationType.NEWS,
                    LanguageCode.JA,
                    mapOf("title" to "K-Bap", "body" to "테스트 알림입니다."),
                )
                content.title shouldBe "(광고) K-Bap"
                content.body shouldBe "테스트 알림입니다.\n" + PushTemplates.optOutNotice.getValue(LanguageCode.JA)
            }

            then("긴 본문은 수신거부 안내를 남기고 본문만 절단돼 1000자에 맞춘다") {
                val content = renderer.render(
                    NotificationType.NEWS,
                    LanguageCode.KO,
                    mapOf("title" to "t", "body" to "x".repeat(1200)),
                )
                content.body.length shouldBe 1000
                content.body shouldEndWith PushTemplates.optOutNotice.getValue(LanguageCode.KO)
            }
        }

        `when`("유형별 광고성 여부를 보면") {
            then("SCAN_SUGGESTION·NEWS·MEAL_TIME 이 광고성이다") {
                NotificationType.entries.filter { it.marketing } shouldBe
                    listOf(NotificationType.SCAN_SUGGESTION, NotificationType.NEWS, NotificationType.MEAL_TIME)
            }
        }
    }
})

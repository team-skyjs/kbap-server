package com.kbap.common.domain.notification

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.notification.model.MealSlot
import com.kbap.common.domain.notification.model.NotificationType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.springframework.context.NoSuchMessageException
import java.util.Locale

class PushMessageRendererTest : BehaviorSpec({
    val messageSource = PushMessageSourceConfig().messageSource()
    val first = PushMessageRenderer(messageSource) { 0 }
    val args = mapOf("food" to "김치찌개", "title" to "t", "body" to "b")
    val optOutKo = "수신거부: 프로필 > 알림 설정"
    val optOutEn = "Turn off: Profile > Notification settings"

    fun message(code: String, lang: LanguageCode): String? = messageSource.getMessage(code, null, null, lang.locale)

    fun variantCount(prefix: String, lang: LanguageCode): Int =
        generateSequence(1) { it + 1 }.takeWhile { message("$prefix.$it.title", lang) != null }.count()

    val slotted = mapOf(NotificationType.SCAN_SUGGESTION to MealSlot.entries)

    given("메시지 파일 누락 검증") {
        `when`("전 유형 × 전 언어의 후보를 세면") {
            then("유형마다 후보가 1개 이상이고 언어 간 후보 수가 같으며 각 후보에 제목·본문이 모두 있다") {
                NotificationType.entries.forEach { type ->
                    val prefix = "push.${type.name.lowercase()}"
                    val counts = LanguageCode.entries.associateWith { variantCount(prefix, it) }
                    withClue("$prefix 후보 수: $counts") {
                        counts.values.toSet().size shouldBe 1
                        counts.getValue(LanguageCode.KO) shouldBeGreaterThanOrEqual 1
                    }
                    LanguageCode.entries.forEach { lang ->
                        (1..counts.getValue(lang)).forEach { n ->
                            withClue("$prefix.$n.body @${lang.code}") { message("$prefix.$n.body", lang).shouldNotBeBlank() }
                        }
                    }
                }
            }

            then("슬롯 문구를 가진 유형은 전 슬롯 × 전 언어에 후보가 있고 언어 간 후보 수가 같다") {
                slotted.forEach { (type, slots) ->
                    slots.forEach { slot ->
                        val prefix = "push.${type.name.lowercase()}.${slot.name.lowercase()}"
                        val counts = LanguageCode.entries.associateWith { variantCount(prefix, it) }
                        withClue("$prefix 후보 수: $counts") {
                            counts.values.toSet().size shouldBe 1
                            counts.getValue(LanguageCode.KO) shouldBeGreaterThanOrEqual 1
                        }
                        LanguageCode.entries.forEach { lang ->
                            (1..counts.getValue(lang)).forEach { n ->
                                withClue("$prefix.$n.body @${lang.code}") { message("$prefix.$n.body", lang).shouldNotBeBlank() }
                            }
                        }
                    }
                }
            }

            then("수신거부 안내가 전 언어에 있다") {
                LanguageCode.entries.forEach { lang ->
                    withClue("push.opt-out @${lang.code}") { message("push.opt-out", lang).shouldNotBeBlank() }
                }
            }
        }

        `when`("지원하지 않는 로케일로 조회하면") {
            then("기본 파일·시스템 로케일로 폴백하지 않고 예외를 던진다") {
                shouldThrow<NoSuchMessageException> { messageSource.getMessage("push.opt-out", null, Locale.FRENCH) }
            }
        }
    }

    given("LanguageCode → Locale 매핑") {
        `when`("간체·번체 중국어를 렌더하면") {
            then("각각 자기 파일의 문구가 나오고 서로 다르다") {
                message("push.opt-out", LanguageCode.ZH_HANS) shouldBe "关闭通知：个人资料 > 通知设置"
                message("push.opt-out", LanguageCode.ZH_HANT) shouldBe "關閉通知：個人資料 > 通知設定"
            }
        }

        `when`("전 언어 코드를 Locale 로 바꾸면") {
            then("언어 태그가 코드와 일치한다") {
                LanguageCode.entries.forEach { it.locale.toLanguageTag() shouldBe it.code }
            }
        }
    }

    given("후보 랜덤 선택") {
        `when`("선택기가 마지막 후보를 고르면") {
            then("선택기는 후보 수를 받고 렌더 결과는 그 번호의 제목·본문 쌍이다") {
                var received = 0
                val last = PushMessageRenderer(messageSource) { count -> received = count; count - 1 }
                val content = last.render(NotificationType.HELPFUL, LanguageCode.KO, args)
                received shouldBe variantCount("push.helpful", LanguageCode.KO)
                content.title shouldBe message("push.helpful.$received.title", LanguageCode.KO)
                content.body shouldBe message("push.helpful.$received.body", LanguageCode.KO)!!.replace("{food}", "김치찌개")
            }
        }

        `when`("기본 선택기로 여러 번 렌더하면") {
            then("후보 전체 집합 안의 문구만 나온다") {
                val random = PushMessageRenderer(messageSource)
                val titles = (1..variantCount("push.helpful", LanguageCode.EN)).map { message("push.helpful.$it.title", LanguageCode.EN) }
                repeat(30) { titles shouldContain random.render(NotificationType.HELPFUL, LanguageCode.EN, args).title }
            }
        }
    }

    given("푸시 템플릿 렌더러") {
        `when`("전 유형 × 전 언어를 렌더하면") {
            then("제목·본문이 비어 있지 않고 길이 상한 안에 있으며 이모지가 보존된다") {
                NotificationType.entries.forEach { type ->
                    LanguageCode.entries.forEach { lang ->
                        val content = first.render(type, lang, args)
                        content.title.shouldNotBeBlank()
                        content.body.shouldNotBeBlank()
                        content.title.length shouldBeLessThanOrEqual 200
                        content.body.length shouldBeLessThanOrEqual 1000
                    }
                }
                first.render(NotificationType.HELPFUL, LanguageCode.KO, args).title shouldContain "👀"
                first.render(NotificationType.HELPFUL, LanguageCode.TH, args).title shouldContain "👀"
            }
        }

        `when`("스캔 제안을 점심·저녁 슬롯으로 렌더하면") {
            then("슬롯별 문구가 나오고 점심·저녁이 서로 다르며 광고 표기가 붙는다") {
                MealSlot.entries.forEach { slot ->
                    LanguageCode.entries.forEach { lang ->
                        val content = first.render(NotificationType.SCAN_SUGGESTION, lang, emptyMap(), slot)
                        content.title shouldStartWith "(광고) "
                        content.body shouldEndWith message("push.opt-out", lang)!!
                    }
                }
                val lunch = first.render(NotificationType.SCAN_SUGGESTION, LanguageCode.KO, emptyMap(), MealSlot.LUNCH)
                val dinner = first.render(NotificationType.SCAN_SUGGESTION, LanguageCode.KO, emptyMap(), MealSlot.DINNER)
                lunch.title shouldBe "(광고) 점심, 어떤 음식일까요? 👀"
                dinner.title shouldBe "(광고) 저녁, 어떤 음식일까요? 👀"
                lunch shouldNotBe dinner
            }

            then("슬롯 문구가 없는 유형은 기본 템플릿으로 떨어진다") {
                first.render(NotificationType.HELPFUL, LanguageCode.KO, mapOf("food" to "김치찌개"), MealSlot.LUNCH) shouldBe
                    first.render(NotificationType.HELPFUL, LanguageCode.KO, mapOf("food" to "김치찌개"))
            }
        }

        `when`("치환 키를 넘기면") {
            then("{food} 가 값으로 바뀐다") {
                val content = first.render(NotificationType.HELPFUL, LanguageCode.KO, args)
                content.body shouldContain "김치찌개"
                content.body shouldNotContain "{food}"
            }

            then("주지 않은 키는 빈 문자열로 치환된다") {
                val content = first.render(NotificationType.REVIEW_REMINDER, LanguageCode.KO, emptyMap())
                content.title shouldNotContain "{"
                content.body shouldNotContain "{"
            }
        }

        `when`("광고성 유형을 렌더하면") {
            then("제목 앞 (광고) 와 본문 끝 수신거부 안내가 붙는다") {
                val content = first.render(NotificationType.SCAN_SUGGESTION, LanguageCode.EN, args)
                content.title shouldStartWith "(광고) "
                content.body shouldEndWith optOutEn
            }
        }

        `when`("정보성 유형을 렌더하면") {
            then("광고 표기와 안내가 없다") {
                val content = first.render(NotificationType.HELPFUL, LanguageCode.KO, args)
                content.title shouldNotContain "(광고)"
                content.body shouldNotContain optOutKo
            }
        }

        `when`("NEWS 를 렌더하면") {
            then("title·body 인자가 통과하고 광고 표기·수신거부 안내가 붙는다") {
                val content = first.render(
                    NotificationType.NEWS,
                    LanguageCode.JA,
                    mapOf("title" to "K-Bap", "body" to "테스트 알림입니다."),
                )
                content.title shouldBe "(광고) K-Bap"
                content.body shouldBe "테스트 알림입니다.\n" + message("push.opt-out", LanguageCode.JA)
            }

            then("긴 본문은 수신거부 안내를 남기고 본문만 절단돼 1000자에 맞춘다") {
                val content = first.render(
                    NotificationType.NEWS,
                    LanguageCode.KO,
                    mapOf("title" to "t", "body" to "x".repeat(1200)),
                )
                content.body.length shouldBe 1000
                content.body shouldEndWith optOutKo
            }
        }

        `when`("유형별 광고성 여부를 보면") {
            then("SCAN_SUGGESTION·NEWS·MEAL_TIME 이 광고성이다") {
                NotificationType.entries.filter { it.marketing } shouldContainExactly
                    listOf(NotificationType.SCAN_SUGGESTION, NotificationType.NEWS, NotificationType.MEAL_TIME)
            }
        }
    }
})

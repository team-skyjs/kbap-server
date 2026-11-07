package com.kbap.batch.notification.discovery

import com.kbap.batch.BatchIntegrationTest
import com.kbap.batch.notification.FakePushClient
import com.kbap.batch.notification.MutableClock
import com.kbap.batch.notification.PushDispatchMetric
import com.kbap.batch.trigger.rest.BatchJobLaunchResult
import com.kbap.batch.trigger.rest.BatchJobLauncher
import com.kbap.common.domain.bookmark.BookmarkJpaRepository
import com.kbap.common.domain.bookmark.model.Bookmark
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodMarketingPopularJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.food.model.FoodMarketingPopular
import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.SocialIdentity
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.domain.notification.NotificationConsentJpaRepository
import com.kbap.common.domain.notification.NotificationDeviceJpaRepository
import com.kbap.common.domain.notification.NotificationDispatchJpaRepository
import com.kbap.common.domain.notification.NotificationJpaRepository
import com.kbap.common.domain.notification.NotificationSettingJpaRepository
import com.kbap.common.domain.notification.model.DevicePlatform
import com.kbap.common.domain.notification.model.Notification
import com.kbap.common.domain.notification.model.NotificationConsent
import com.kbap.common.domain.notification.model.NotificationConsentType
import com.kbap.common.domain.notification.model.NotificationDevice
import com.kbap.common.domain.notification.model.NotificationSetting
import com.kbap.common.domain.notification.model.NotificationType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.batch.core.job.JobExecution
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

@BatchIntegrationTest
class FoodDiscoveryPushJobTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var launcher: BatchJobLauncher

    @Autowired private lateinit var deviceRepository: NotificationDeviceJpaRepository

    @Autowired private lateinit var settingRepository: NotificationSettingJpaRepository

    @Autowired private lateinit var consentRepository: NotificationConsentJpaRepository

    @Autowired private lateinit var notificationRepository: NotificationJpaRepository

    @Autowired private lateinit var dispatchRepository: NotificationDispatchJpaRepository

    @Autowired private lateinit var memberRepository: MemberJpaRepository

    @Autowired private lateinit var foodRepository: FoodJpaRepository

    @Autowired private lateinit var popularRepository: FoodMarketingPopularJpaRepository

    @Autowired private lateinit var bookmarkRepository: BookmarkJpaRepository

    @Autowired private lateinit var fakePushClient: FakePushClient

    @Autowired private lateinit var clock: MutableClock

    @Autowired private lateinit var meterRegistry: MeterRegistry

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    private val createdMembers = mutableListOf<Long>()
    private val createdFoods = mutableListOf<Long>()

    private fun nowJvm(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault())

    private fun clear() {
        dispatchRepository.deleteAll()
        notificationRepository.deleteAll()
        deviceRepository.deleteAll()
        settingRepository.deleteAll()
        consentRepository.deleteAll()
        bookmarkRepository.deleteAll()
        popularRepository.deleteAll()
        foodRepository.deleteAllById(createdFoods)
        memberRepository.deleteAllById(createdMembers)
        createdFoods.clear()
        createdMembers.clear()
        fakePushClient.reset()
    }

    private fun food(name: String, vararg ingredients: Pair<IngredientCode, Int>, translations: Map<String, String> = mapOf("en" to "$name in English")): Food {
        val saved = foodRepository.save(
            Food(
                koreanName = name,
                description = "$name 설명",
                descriptionTranslations = translations,
                ingredients = ingredients.map { (code, percent) -> FoodIngredient(code.name, percent) },
            ),
        )
        popularRepository.save(FoodMarketingPopular(saved.id))
        createdFoods += saved.id
        return saved
    }

    private fun member(vararg avoided: IngredientCode, langs: List<String> = listOf("ko")): Long {
        val member = Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "fd-${UUID.randomUUID()}", null))
        member.avoidanceSubstanceCodes = avoided.map { it.name }
        val id = memberRepository.save(member).id
        createdMembers += id
        langs.forEach { lang ->
            val inst = "fd-${UUID.randomUUID()}".take(36)
            deviceRepository.save(NotificationDevice.register(inst, "ExponentPushToken[$inst]", DevicePlatform.ANDROID, lang, id))
            settingRepository.save(NotificationSetting(memberId = id, installationId = inst, news = true))
        }
        NotificationConsentType.entries.forEach {
            consentRepository.save(NotificationConsent.grantForMember(id, null, it, 2, LocalDateTime.now()))
        }
        return id
    }

    private fun stampCreatedAtToClock() {
        jdbcTemplate.update("UPDATE notification SET created_at = ? WHERE created_at > ?", nowJvm(), nowJvm())
    }

    private fun history(memberId: Long, foodId: Long, daysAgo: Long) {
        val saved = notificationRepository.save(Notification(memberId = memberId, type = NotificationType.FOOD_DISCOVERY, title = "t", body = "b", foodId = foodId))
        jdbcTemplate.update("UPDATE notification SET created_at = ? WHERE id = ?", nowJvm().minusDays(daysAgo), saved.id)
    }

    private fun noCandidate(slot: String): Double = meterRegistry.counter(PushDispatchMetric.FOOD_DISCOVERY_NO_CANDIDATE, "slot", slot).count()

    private fun sentCounter(): Double = meterRegistry.counter(PushDispatchMetric.NAME, "type", "FOOD_DISCOVERY", "result", "sent").count()

    private fun run(): JobExecution {
        val started = launcher.launch(FoodDiscoveryPushBatchConfig.JOB_NAME).shouldBeInstanceOf<BatchJobLaunchResult.Started>()
        repeat(200) {
            val execution = launcher.getExecution(started.execution.id)!!
            if (!execution.isRunning) return execution
            Thread.sleep(100)
        }
        error("잡이 20초 안에 끝나지 않았습니다")
    }

    init {
        given("음식 발견 발송 잡") {
            `when`("09:30 에 돼지고기 회피 회원과 돼지고기 10%(CAUTION) 음식·안전 음식이 있으면") {
                clear()
                clock.setSeoul(2026, 11, 4, 9, 30)
                food("돼지국밥", IngredientCode.PORK to 10, IngredientCode.WHEAT to 10)
                val safe = food("삼계탕", IngredientCode.POULTRY to 80).apply { imageRef = "https://cdn.test/samgyetang.jpg" }.let(foodRepository::save)
                val memberId = member(IngredientCode.PORK)
                val before = sentCounter()

                val execution = run()

                then("안전 음식으로 광고 문구를 보내고 알림함 행 food_id 와 data.foodId 가 그 음식이다") {
                    execution.exitStatus.exitCode shouldBe "COMPLETED"
                    val sent = fakePushClient.sent.single()
                    sent.title shouldBe "(광고) 🌟 삼계탕 드셔보셨어요?"
                    sent.body shouldBe "삼계탕 설명\n수신거부: 프로필 > 알림 설정"
                    sent.channelId shouldBe "news"
                    sent.imageUrl shouldBe "https://cdn.test/samgyetang.jpg"
                    (sent.data["foodId"] as Number).toLong() shouldBe safe.id
                    sent.data["type"] shouldBe "FOOD_DISCOVERY"
                    val notification = notificationRepository.findAll().single()
                    notification.memberId shouldBe memberId
                    notification.foodId shouldBe safe.id
                    (sent.data["notificationId"] as Number).toLong() shouldBe notification.id
                    sentCounter() - before shouldBe 1.0
                }

                then("같은 슬롯에 다시 실행하면 추가 발송이 없다") {
                    stampCreatedAtToClock()
                    clock.setSeoul(2026, 11, 4, 11, 0)
                    run()
                    fakePushClient.sent shouldHaveSize 1
                }
            }

            `when`("21:30 에 실행하면") {
                clear()
                clock.setSeoul(2026, 11, 4, 21, 30)
                food("삼계탕", IngredientCode.POULTRY to 80)
                member()

                val execution = run()

                then("발송하지 않는다") {
                    execution.exitStatus.exitCode shouldBe "COMPLETED"
                    fakePushClient.sent shouldHaveSize 0
                    notificationRepository.findAll() shouldHaveSize 0
                }
            }

            `when`("후보가 회피·번역 누락·90일 이력·북마크로 모두 빠지는 회원이 있으면") {
                clear()
                clock.setSeoul(2026, 11, 4, 12, 0)
                food("돼지국밥", IngredientCode.PORK to 10, translations = mapOf("th" to "th 설명"))
                val noThai = food("비빔밥")
                val recent = food("삼계탕", translations = mapOf("th" to "th 설명"))
                val bookmarked = food("김밥", translations = mapOf("th" to "th 설명"))
                val oldHistory = food("떡볶이", translations = mapOf("th" to "th 설명"))
                val blocked = member(IngredientCode.PORK, langs = listOf("th"))
                history(blocked, recent.id, 89)
                history(blocked, oldHistory.id, 91)
                bookmarkRepository.save(Bookmark(blocked, bookmarked.id))
                val noneLeft = member(IngredientCode.MEAT, langs = listOf("ko"))
                history(noneLeft, noThai.id, 1)
                history(noneLeft, recent.id, 1)
                history(noneLeft, oldHistory.id, 1)
                bookmarkRepository.save(Bookmark(noneLeft, bookmarked.id))
                val before = noCandidate("12")

                run()

                then("91일 전 이력 음식은 다시 후보가 되어 그것만 보낸다") {
                    val sent = fakePushClient.sent.single()
                    (sent.data["foodId"] as Number).toLong() shouldBe oldHistory.id
                    notificationRepository.findAll().filter { it.createdAt > nowJvm().minusMinutes(1) }.map { it.memberId } shouldBe listOf(blocked)
                }

                then("후보가 없는 회원은 건너뛰고 no_candidate{slot=12} 가 1 오른다") {
                    noCandidate("12") - before shouldBe 1.0
                }
            }

            `when`("같은 음식이 뽑히는 회원 3명이면") {
                clear()
                clock.setSeoul(2026, 11, 4, 15, 0)
                food("삼계탕", IngredientCode.POULTRY to 80)
                repeat(3) { member() }

                run()

                then("발송 요청은 음식당 1회로 묶인다") {
                    fakePushClient.batches shouldBe listOf(3)
                    notificationRepository.findAll().map { it.foodId }.toSet() shouldHaveSize 1
                }
            }
        }
    }
}

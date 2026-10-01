package com.kbap.batch.stats

import com.jayway.jsonpath.JsonPath
import com.kbap.batch.BatchIntegrationTest
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.port.teamchannel.TeamChannelSender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.sql.DataSource

@BatchIntegrationTest
class DailyUserStatsReporterTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var memberRepository: MemberJpaRepository

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var mockMvc: MockMvc

    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")

    init {
        val runAt = ZonedDateTime.of(2026, 1, 15, 9, 0, 0, 0, seoul)
        val clock = Clock.fixed(runAt.toInstant(), seoul)
        val ids = 8801L..8830L

        fun exec(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

        fun clear() {
            exec("DELETE FROM notification_device WHERE member_id BETWEEN ${ids.first} AND ${ids.last}")
            exec("DELETE FROM member WHERE id BETWEEN ${ids.first} AND ${ids.last}")
        }

        fun jvmTimeOf(kst: LocalDateTime): LocalDateTime =
            kst.atZone(seoul).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()

        fun seedMember(id: Long, kstCreatedAt: LocalDateTime, email: String? = "real$id@example.com", country: String? = "KR", bot: Boolean = false) =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO member (id, provider, provider_uid, email, country_code, member_status,
                                        onboarding_completed, scan_count, scan_unlocked, review_count,
                                        unique_reviewed_food_count, avoidance_substance_codes, diet_categories,
                                        is_bot, status, created_at, updated_at)
                    VALUES (?, 'GOOGLE', ?, ?, ?, 'ACTIVE', 1, 0, 0, 0, 0, JSON_ARRAY(), JSON_ARRAY(), ?, 'ACTIVE', ?, ?)
                    """,
                ).use { ps ->
                    val at = jvmTimeOf(kstCreatedAt)
                    ps.setLong(1, id)
                    ps.setString(2, "stats-$id")
                    ps.setString(3, email)
                    ps.setString(4, country)
                    ps.setBoolean(5, bot)
                    ps.setObject(6, at)
                    ps.setObject(7, at)
                    ps.executeUpdate()
                }
            }

        fun seedDevice(memberId: Long, platform: String) = exec(
            "INSERT INTO notification_device (installation_id, member_id, expo_token, platform, lang, status, created_at, updated_at) " +
                "VALUES ('inst-$memberId', $memberId, 'ExponentPushToken[$memberId]', '$platform', 'en', 'ACTIVE', NOW(6), NOW(6))",
        )

        val yesterday = LocalDate.of(2026, 1, 14)

        fun reporter(sender: TeamChannelSender?, excluded: Set<Long> = emptySet(), enabled: Boolean = true) =
            DailyUserStatsReporter(memberRepository, sender, clock, excluded, enabled)

        fun logsOf(block: () -> Unit): List<ch.qos.logback.classic.spi.ILoggingEvent> {
            val logger = org.slf4j.LoggerFactory.getLogger(DailyUserStatsReporter::class.java) as ch.qos.logback.classic.Logger
            val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                block()
            } finally {
                logger.detachAppender(appender)
            }
            return appender.list.toList()
        }

        beforeEach { clear() }
        afterSpec { clear() }

        given("일일 유저 통계 집계") {
            `when`("로봇 2명·시드 1명·리뷰 봇 1명·실유저 2명이 전일 가입했으면") {
                then("신규는 실유저 2명만 센다 — 활성 유저도 같은 필터로 센다") {
                    val activeBefore = memberRepository.countActiveRealMembers(setOf(8803L))
                    seedMember(8801, yesterday.atTime(10, 0), email = "robot.12345@gmail.com")
                    seedMember(8802, yesterday.atTime(11, 0), email = "tester@cloudtestlabaccounts.com")
                    seedMember(8803, yesterday.atTime(12, 0), email = "seed@example.com")
                    seedMember(8804, yesterday.atTime(13, 0))
                    seedMember(8805, yesterday.atTime(14, 0), email = null)
                    seedMember(8806, yesterday.atTime(15, 0), bot = true)

                    val stats = reporter(null, excluded = setOf(8803L)).collect()

                    stats.date shouldBe yesterday
                    stats.newMembers shouldBe 2
                    stats.activeMembers shouldBe activeBefore + 2
                }
            }

            `when`("KST 경계 직전·직후에 가입했으면") {
                then("전일 23:59:59 KST 는 전일, 당일 00:00 KST 는 제외, 전전일 23:59 는 전일 대비 비교에 들어간다") {
                    seedMember(8811, yesterday.atTime(23, 59, 59))
                    seedMember(8812, yesterday.plusDays(1).atStartOfDay())
                    seedMember(8813, yesterday.atStartOfDay())
                    seedMember(8814, yesterday.minusDays(1).atTime(23, 59))

                    val stats = reporter(null).collect()

                    stats.newMembers shouldBe 2
                    stats.previousDayNewMembers shouldBe 1
                }
            }

            `when`("국가·플랫폼이 섞여 있으면") {
                then("국가(null=미설정)·플랫폼(기기 없음=미상)별로 나누고 메시지에 증감을 적는다") {
                    seedMember(8821, yesterday.atTime(9, 0), country = "JP")
                    seedMember(8822, yesterday.atTime(9, 30), country = "JP")
                    seedMember(8823, yesterday.atTime(10, 0), country = null)
                    seedDevice(8821, "IOS")
                    seedDevice(8822, "ANDROID")

                    val stats = reporter(null).collect()
                    stats.byCountry shouldBe mapOf("JP" to 2, DailyUserStatsReporter.UNSET_COUNTRY to 1)
                    stats.byPlatform shouldBe mapOf("iOS" to 1, "Android" to 1, DailyUserStatsReporter.UNKNOWN_PLATFORM to 1)

                    val text = DailyUserStatsReporter.render(stats)
                    text shouldContain "2026-01-14"
                    text shouldContain "신규 가입: 3명 (전일 0명, +3)"
                    text shouldContain "JP 2 · 미설정 1"
                }
            }
        }

        given("일일 유저 통계 켜기·끄기(kbap.batch.user-stats.enabled)") {
            `when`("켜져 있는데 웹훅 URL 이 없으면(prod 누락)") {
                then("첫 회 ERROR 로 알린다 — 누락 감지는 그대로") {
                    val logs = logsOf { reporter(null).report() shouldBe DailyUserStatsReporter.Outcome.SKIPPED }

                    logs.count { it.level == ch.qos.logback.classic.Level.ERROR } shouldBe 1
                }
            }

            `when`("꺼져 있으면(dev)") {
                then("웹훅이 없어도 ERROR·WARN 없이 INFO 한 줄로 SKIPPED — 배포마다 Sentry 가 울리지 않는다") {
                    val logs = logsOf { reporter(null, enabled = false).report() shouldBe DailyUserStatsReporter.Outcome.SKIPPED }

                    logs.none { it.level.isGreaterOrEqual(ch.qos.logback.classic.Level.WARN) } shouldBe true
                    logs.count { it.level == ch.qos.logback.classic.Level.INFO } shouldBe 1
                }
            }

            `when`("꺼져 있으면 웹훅이 있어도") {
                then("보내지 않는다") {
                    var calls = 0
                    reporter({ calls++ }, enabled = false).report() shouldBe DailyUserStatsReporter.Outcome.SKIPPED
                    calls shouldBe 0
                }
            }

            `when`("프로필 설정을 보면") {
                then("dev 는 끄고 prod·기본은 켠다") {
                    fun propertyOf(file: String): Any? =
                        org.springframework.beans.factory.config.YamlPropertiesFactoryBean().apply {
                            setResources(org.springframework.core.io.FileSystemResource("src/main/resources/$file"))
                        }.getObject()!!["kbap.batch.user-stats.enabled"]

                    propertyOf("application-dev.yml") shouldBe false
                    propertyOf("application-prod.yml") shouldBe null
                    propertyOf("application.yml") shouldBe "\${USER_STATS_ENABLED:true}"
                }
            }
        }

        given("일일 유저 통계 발송") {
            `when`("웹훅이 설정되지 않았으면") {
                then("보내지 않고 SKIPPED 로 끝난다 — 통과와 안 돌았음을 구분한다") {
                    reporter(null).report() shouldBe DailyUserStatsReporter.Outcome.SKIPPED
                }
            }

            `when`("첫 발송이 실패하면") {
                then("1회 재시도해 보낸다") {
                    val sent = mutableListOf<String>()
                    var calls = 0
                    val flaky = TeamChannelSender { text ->
                        calls++
                        if (calls == 1) throw IllegalStateException("slack 503")
                        sent += text
                    }

                    reporter(flaky).report() shouldBe DailyUserStatsReporter.Outcome.SENT
                    calls shouldBe 2
                    sent.single() shouldContain "일일 유저 통계"
                }
            }

            `when`("재시도까지 실패하면") {
                then("예외로 끝나 잡이 FAILED 로 남는다(다른 잡과는 무관)") {
                    var calls = 0
                    val broken = TeamChannelSender { calls++; throw IllegalStateException("slack down") }

                    shouldThrow<IllegalStateException> { reporter(broken).report() }
                    calls shouldBe 2
                }
            }

            `when`("웹훅 없이 잡을 트리거하면") {
                then("잡은 COMPLETED 이고 스텝 종료 코드가 SKIPPED 다") {
                    val body = mockMvc.post("/internal/batch/jobs?jobName=${DailyUserStatsBatchConfig.JOB}")
                        .andExpect { status { isAccepted() } }
                        .andReturn().response.contentAsString
                    val executionId = JsonPath.read<Int>(body, "$.executionId").toLong()
                    var status = ""
                    repeat(100) {
                        status = JsonPath.read(
                            mockMvc.get("/internal/batch/executions/$executionId").andReturn().response.contentAsString,
                            "$.status",
                        )
                        if (status != "STARTING" && status != "STARTED") return@repeat
                        Thread.sleep(100)
                    }

                    status shouldBe "COMPLETED"
                    dataSource.connection.use { c ->
                        c.prepareStatement("SELECT EXIT_CODE FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = ?").use { ps ->
                            ps.setLong(1, executionId)
                            ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
                        }
                    } shouldBe DailyUserStatsBatchConfig.SKIPPED
                }
            }
        }
    }
}

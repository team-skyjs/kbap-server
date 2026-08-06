package com.kbap.api.home

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.core.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class HomeControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        fun token(memberId: Long) = tokenIssuer.issueAccessToken(memberId, MemberRole.USER)

        fun home(memberId: Long?, lang: String = "en") = mockMvc.get("/api/v1/home?lang=$lang") {
            memberId?.let { header("Authorization", "Bearer ${token(it)}") }
        }

        fun payload(memberId: Long?, lang: String = "en") =
            mapper.readTree(
                home(memberId, lang).andReturn().response.getContentAsString(Charsets.UTF_8),
            ).path("payload")

        beforeContainer {
            HomeTestSeed.reset(dataSource)
        }

        given("기피 성분과 스캔 이력이 있는 회원") {
            `when`("홈을 조회하면") {
                then("기피 성분·인기 음식·최근 스캔 세 섹션이 한 응답에 담긴다") {
                    HomeTestSeed.seedReadyFoods(dataSource, count = 7)
                    HomeTestSeed.seedMember(dataSource, memberId = 11L, codes = listOf("EGG"))
                    HomeTestSeed.seedScan(dataSource, memberId = 11L, foodId = 3L, scannedAt = "2026-07-01 10:00:00")
                    HomeTestSeed.seedScan(dataSource, memberId = 11L, foodId = 1L, scannedAt = "2026-07-02 10:00:00")

                    val payload = payload(11L)

                    payload.path("authenticated").asBoolean() shouldBe true
                    payload.path("avoidedSubstances").map { it.path("code").asText() } shouldContainExactly listOf("EGG")
                    payload.path("popularFoods").size() shouldBe 5
                    payload.path("popularFoods").forEach {
                        it.path("imageRef").asText() shouldBe "https://cdn.test/menu-${it.path("foodId").asLong()}.png"
                    }
                    payload.path("recentScans").map { it.path("foodId").asLong() } shouldContainExactly listOf(1L, 3L)
                }
            }

            `when`("같은 메뉴를 여러 번 스캔했으면") {
                then("최근 스캔에 중복 없이 최신 1건만 나타난다") {
                    HomeTestSeed.seedReadyFoods(dataSource, count = 3)
                    HomeTestSeed.seedMember(dataSource, memberId = 11L, codes = emptyList())
                    HomeTestSeed.seedScan(dataSource, memberId = 11L, foodId = 1L, scannedAt = "2026-07-01 10:00:00")
                    HomeTestSeed.seedScan(dataSource, memberId = 11L, foodId = 2L, scannedAt = "2026-07-02 10:00:00")
                    HomeTestSeed.seedScan(dataSource, memberId = 11L, foodId = 1L, scannedAt = "2026-07-03 10:00:00")

                    val recent = payload(11L).path("recentScans").map { it.path("foodId").asLong() }

                    recent shouldContainExactly listOf(1L, 2L)
                }
            }
        }

        given("폐기된 appLanguage 키가 남아 있는 회원") {
            `when`("lang=ja 로 조회하면") {
                then("음식명과 기피 성분명이 일본어로 내려온다") {
                    HomeTestSeed.seedReadyFoods(dataSource, count = 1)
                    HomeTestSeed.seedMember(dataSource, memberId = 11L, codes = listOf("EGG"))

                    val payload = payload(11L, lang = "ja")

                    payload.path("avoidedSubstances").single().path("name").asText() shouldBe "卵"
                    payload.path("popularFoods").single().path("name").asText() shouldBe "メニュー1"
                }
            }

            `when`("프로필과 다른 lang=ko 로 조회하면") {
                then("저장된 값과 무관하게 한국어로 내려온다") {
                    HomeTestSeed.seedReadyFoods(dataSource, count = 1)
                    HomeTestSeed.seedMember(dataSource, memberId = 11L, codes = listOf("EGG"))

                    val payload = payload(11L, lang = "ko")

                    payload.path("avoidedSubstances").single().path("name").asText() shouldBe "계란"
                    payload.path("popularFoods").single().path("name").asText() shouldBe "메뉴1"
                }
            }

            `when`("같은 lang 으로 비회원이 조회하면") {
                then("회원과 같은 언어로 내려온다") {
                    HomeTestSeed.seedReadyFoods(dataSource, count = 1)
                    HomeTestSeed.seedMember(dataSource, memberId = 11L, codes = listOf("EGG"))

                    val 회원 = payload(11L, lang = "ko").path("popularFoods").single().path("name").asText()
                    val 비회원 = payload(null, lang = "ko").path("popularFoods").single().path("name").asText()

                    회원 shouldBe 비회원
                }
            }
        }

        given("온보딩 미완료 회원") {
            `when`("lang=ja 로 조회하면") {
                then("프로필과 무관하게 일본어로 응답한다") {
                    HomeTestSeed.seedReadyFoods(dataSource, count = 1)
                    dataSource.connection.use { c ->
                        c.createStatement().use {
                            it.execute(
                                "INSERT INTO member (id, provider, provider_uid, email, nickname, member_status, " +
                                    "onboarding_completed, status, created_at, updated_at) " +
                                    "VALUES (12, 'GOOGLE', 'home-test-nolang', NULL, NULL, " +
                                    "'ACTIVE', 0, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                            )
                        }
                    }

                    val payload = payload(12L, lang = "ja")

                    payload.path("popularFoods").single().path("name").asText() shouldBe "メニュー1"
                }
            }
        }

        given("기피 성분·스캔 이력이 없는 회원") {
            `when`("홈을 조회하면") {
                then("두 섹션이 빈 배열로 내려온다") {
                    HomeTestSeed.seedReadyFoods(dataSource, count = 2)
                    HomeTestSeed.seedMember(dataSource, memberId = 11L, codes = emptyList())

                    val payload = payload(11L)

                    payload.path("avoidedSubstances").isEmpty shouldBe true
                    payload.path("avoidedSubstances").isNull shouldBe false
                    payload.path("recentScans").isEmpty shouldBe true
                    payload.path("recentScans").isNull shouldBe false
                    payload.path("popularFoods").size() shouldBe 2
                }
            }
        }

        given("기피 성분을 100% 포함한 음식") {
            `when`("그 성분을 회피하는 회원이 홈을 조회하면") {
                then("본인 프로필 기준으로 DANGER 판정이 내려온다") {
                    HomeTestSeed.seedReadyFoods(dataSource, count = 1)
                    HomeTestSeed.seedFoodSubstance(dataSource, foodId = 1L, code = "EGG", percent = 100)
                    HomeTestSeed.seedMember(dataSource, memberId = 11L, codes = listOf("EGG"))

                    payload(11L).path("popularFoods").single().path("overallRiskStatus").asText() shouldBe "DANGER"
                }
            }
        }
    }
}

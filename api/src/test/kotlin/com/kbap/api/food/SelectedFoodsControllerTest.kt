package com.kbap.api.food

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.domain.member.model.MemberRole
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.PayloadApplicationEvent
import org.springframework.context.event.ApplicationEventMulticaster
import java.util.concurrent.atomic.AtomicInteger

@IntegrationTest
class SelectedFoodsControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var entityManagerFactory: EntityManagerFactory
    @Autowired private lateinit var applicationEventMulticaster: ApplicationEventMulticaster
    private val mapper = jacksonObjectMapper()

    init {
        beforeEach {
            TestTables.clearAll(dataSource)
            (1..10).forEach { id ->
                jdbc.update(
                    """
                    INSERT INTO food (id, korean_name, description, spiciness, name_translations,
                        description_translations, ingredients, content_status, status, created_at, updated_at)
                    VALUES (?, ?, '설명', 0, ?, '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))
                    """.trimIndent(),
                    id, "선정음식$id", mapper.writeValueAsString(mapOf("en" to "Selected $id")),
                )
            }
        }
        afterSpec { TestTables.clearAll(dataSource) }

        given("공개 가능한 선정 음식 열 개") {
            `when`("비회원이 역순 ID로 요약을 요청하면") {
                then("한 응답에서 입력 순서와 기존 요약 계약을 유지한다") {
                    val result = mvc.get("/api/foods/selected") {
                        param("ids", "10,9,8,7,6,5,4,3,2,1")
                        param("lang", "en")
                        header("X-API-Version", "1.1")
                    }.andExpect {
                        status { isOk() }
                        header { string("Cache-Control", "private, no-store") }
                    }.andReturn()
                    val payload = mapper.readTree(result.response.contentAsString).path("payload")
                    payload.path("items").map { it.path("foodId").asInt() } shouldBe (10 downTo 1).toList()
                    payload.path("unavailableFoodIds").size() shouldBe 0
                    payload.path("items").first().path("name").asText() shouldBe "Selected 10"
                    payload.path("items").all { !it.path("bookmarked").asBoolean() } shouldBe true
                }
            }
        }

        given("선정 목록 입력 검증") {
            listOf("", "0", "-1", "+1", "1,1", "1,01", "1,", ",1", "1,,2", "a", "9223372036854775808", "1,2,3,4,5,6,7,8,9,10,11").forEach { ids ->
                `when`("유효하지 않은 ID 목록 '$ids'를 보내면") {
                    then("COMMON-002로 거절한다") {
                        mvc.get("/api/foods/selected") {
                            param("ids", ids)
                            param("lang", "en")
                            header("X-API-Version", "1.1")
                        }.andExpect {
                            status { isBadRequest() }
                            jsonPath("$.code") { value("COMMON-002") }
                        }
                    }
                }
            }
            `when`("ids를 여러 번 지정하면") {
                then("합쳐 처리하지 않고 거절한다") {
                    mvc.get("/api/foods/selected") {
                        param("ids", "1", "2")
                        param("lang", "en")
                    }.andExpect { status { isBadRequest() } }
                }
            }
            listOf("ids", "lang").forEach { missing ->
                `when`("필수 파라미터 $missing 이 없으면") {
                    then("잘못된 요청으로 거절한다") {
                        mvc.get("/api/foods/selected") {
                            if (missing != "ids") param("ids", "1")
                            if (missing != "lang") param("lang", "en")
                        }.andExpect { status { isBadRequest() } }
                    }
                }
            }
            `when`("ID 양끝 공백과 선행 영을 보내면") {
                then("양수 ID로 정규화하고 미지원 언어는 영어로 응답한다") {
                    mvc.get("/api/foods/selected") {
                        param("ids", " 01 ")
                        param("lang", "unsupported")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items[0].foodId") { value(1) }
                        jsonPath("$.payload.items[0].name") { value("Selected 1") }
                    }
                }
            }
        }
        given("존재하지 않거나 공개할 수 없는 음식") {
            `when`("삭제·미공개·정상 음식을 섞어 요청하면") {
                then("공개 음식과 사용할 수 없는 ID 각각의 상대 순서를 유지한다") {
                    jdbc.update("UPDATE food SET status='DELETED' WHERE id=2")
                    jdbc.update("UPDATE food SET content_status='PENDING_REVIEW' WHERE id=3")
                    mvc.get("/api/foods/selected") {
                        param("ids", "3,4,999,2,1")
                        param("lang", "en")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items[0].foodId") { value(4) }
                        jsonPath("$.payload.items[1].foodId") { value(1) }
                        jsonPath("$.payload.items.length()") { value(2) }
                        jsonPath("$.payload.unavailableFoodIds[0]") { value(3) }
                        jsonPath("$.payload.unavailableFoodIds[1]") { value(999) }
                        jsonPath("$.payload.unavailableFoodIds[2]") { value(2) }
                        jsonPath("$.payload.unavailableFoodIds.length()") { value(3) }
                    }
                }
            }
            `when`("요청한 음식이 모두 없으면") {
                then("빈 요약 목록과 전체 누락 ID를 성공 응답한다") {
                    mvc.get("/api/foods/selected") {
                        param("ids", "999,998")
                        param("lang", "en")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items") { isEmpty() }
                        jsonPath("$.payload.unavailableFoodIds[0]") { value(999) }
                        jsonPath("$.payload.unavailableFoodIds[1]") { value(998) }
                        jsonPath("$.payload.unavailableFoodIds.length()") { value(2) }
                    }
                }
            }
        }
        given("선택적 인증과 API 버전") {
            `when`("만료되었거나 잘못된 Bearer 토큰을 보내면") {
                then("비회원 응답으로 우회하지 않고 인증 오류를 반환한다") {
                    mvc.get("/api/foods/selected") {
                        param("ids", "1")
                        param("lang", "en")
                        header("Authorization", "Bearer invalid")
                    }.andExpect { status { isUnauthorized() } }
                }
            }
            listOf("missing", "99.0").forEach { version ->
                `when`("버전이 $version 이면") {
                    then("기존 버전 필수 규칙을 적용한다") {
                        mvc.get("/api/foods/selected") {
                            param("ids", "1")
                            param("lang", "en")
                            if (version == "missing") with { it.removeHeader("X-API-Version"); it }
                            else header("X-API-Version", version)
                        }.andExpect {
                            status { isBadRequest() }
                            jsonPath("$.code") { value("COMMON-002") }
                        }
                    }
                }
            }
        }

        given("개인화된 선정 음식 요약") {
            `when`("회피 성분과 북마크가 다른 두 회원이 조회하면") {
                then("같은 순서에 각 회원 위험도와 북마크를 반영하고 리뷰는 일괄 집계한다") {
                    FoodTestSeed.seedMemberAvoiding(dataSource, 101L, "SOY")
                    FoodTestSeed.seedMemberAvoiding(dataSource, 102L)
                    jdbc.update("UPDATE food SET ingredients=? WHERE id=1", """[{"code":"SOY","inclusion_percent":100}]""")
                    jdbc.update("INSERT INTO bookmark(member_id,food_id,created_at,updated_at) VALUES (101,1,NOW(6),NOW(6))")
                    jdbc.update("INSERT INTO food_review(member_id,food_id,rating,created_at,updated_at) VALUES (101,1,5,NOW(6),NOW(6)),(102,1,3,NOW(6),NOW(6))")
                    listOf(101L to "DANGER", 102L to "SAFE").forEach { (memberId, risk) ->
                        mvc.get("/api/foods/selected") {
                            param("ids", "2,1")
                            param("lang", "en")
                            header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(memberId, MemberRole.USER)}")
                        }.andExpect {
                            status { isOk() }
                            jsonPath("$.payload.items[0].foodId") { value(2) }
                            jsonPath("$.payload.items[1].foodId") { value(1) }
                            jsonPath("$.payload.items.length()") { value(2) }
                            jsonPath("$.payload.items[1].overallRiskStatus") { value(risk) }
                            jsonPath("$.payload.items[1].bookmarked") { value(memberId == 101L) }
                            jsonPath("$.payload.items[1].review.averageRating") { value(4.0) }
                            jsonPath("$.payload.items[1].review.count") { value(2) }
                        }
                    }
                }
            }
            `when`("한 개와 열 개를 조회하면") {
                then("조회 이벤트나 이력을 남기지 않고 음식 수에 따라 쿼리가 증가하지 않는다") {
                    val viewed = AtomicInteger()
                    val listener = ApplicationListener<ApplicationEvent> { event ->
                        if (event is PayloadApplicationEvent<*> && event.payload is FoodViewed) viewed.incrementAndGet()
                    }
                    val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
                    val originallyEnabled = statistics.isStatisticsEnabled
                    applicationEventMulticaster.addApplicationListener(listener)
                    statistics.isStatisticsEnabled = true
                    try {
                        val counts = listOf("1", "10,9,8,7,6,5,4,3,2,1").map { ids ->
                            statistics.clear()
                            mvc.get("/api/foods/selected") {
                                param("ids", ids)
                                param("lang", "en")
                            }.andExpect { status { isOk() } }
                            statistics.prepareStatementCount
                        }
                        (counts.first() > 0) shouldBe true
                        counts.last() shouldBe counts.first()
                        viewed.get() shouldBe 0
                        jdbc.queryForObject("SELECT COUNT(*) FROM food_view_log", Long::class.java) shouldBe 0L
                        jdbc.queryForObject("SELECT COUNT(*) FROM scan_history", Long::class.java) shouldBe 0L
                    } finally {
                        statistics.isStatisticsEnabled = originallyEnabled
                        applicationEventMulticaster.removeApplicationListener(listener)
                    }
                }
            }
        }

        given("통합 브랜치 지원 버전과 Swagger 계약") {
            listOf("1.0", "1.1", "1.2", "1.3", "2.0", "3.0").forEach { version ->
                `when`("지원 버전 $version 으로 요청하면") {
                    then("새 버전 없이 선정 목록 계약을 제공한다") {
                        val baseline = mvc.get("/api/foods/selected") {
                            param("ids", "2,999,1")
                            param("lang", "en")
                            header("X-API-Version", "1.1")
                        }.andExpect { status { isOk() } }.andReturn()
                        val response = mvc.get("/api/foods/selected") {
                            param("ids", "2,999,1")
                            param("lang", "en")
                            header("X-API-Version", version)
                        }.andExpect {
                            status { isOk() }
                            header { string("Cache-Control", "private, no-store") }
                            jsonPath("$.success") { value(true) }
                            jsonPath("$.payload.unavailableFoodIds[0]") { value(999) }
                            jsonPath("$.payload.unavailableFoodIds.length()") { value(1) }
                            jsonPath("$.payload.items[0].foodId") { value(2) }
                            jsonPath("$.payload.items[1].foodId") { value(1) }
                            jsonPath("$.payload.items.length()") { value(2) }
                        }.andReturn()
                        mapper.readTree(response.response.contentAsString) shouldBe mapper.readTree(baseline.response.contentAsString)
                    }
                }
            }
            `when`("1.1 Swagger 문서를 조회하면") {
                then("선정 경로와 필수 ID 및 언어를 명시한다") {
                    val result = mvc.get("/v3/api-docs/1.1").andExpect { status { isOk() } }.andReturn()
                    val operation = mapper.readTree(result.response.contentAsString)
                        .path("paths").path("/api/foods/selected").path("get")
                    operation.isMissingNode shouldBe false
                    val parameters = operation.path("parameters").associateBy { it.path("name").asText() }
                    parameters.getValue("ids").path("required").asBoolean() shouldBe true
                    parameters.getValue("lang").path("required").asBoolean() shouldBe true
                    parameters.containsKey("servletRequest") shouldBe false
                    parameters.containsKey("memberId") shouldBe false
                }
            }
        }
    }
}

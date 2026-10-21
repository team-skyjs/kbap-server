package com.kbap.api.scanbatch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.image.model.UploadedImage
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.member.model.SocialIdentity
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.port.llm.MenuBoardLine
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

@IntegrationTest
class ScanBatchMergeTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokens: TokenIssuer
    @Autowired private lateinit var members: MemberJpaRepository
    @Autowired private lateinit var uploads: UploadedImageJpaRepository
    @Autowired private lateinit var foods: FoodJpaRepository
    @Autowired private lateinit var batchSwitch: ScanBatchSwitch
    @Autowired private lateinit var batches: ScanBatchService

    private val mapper = jacksonObjectMapper()

    init {
        fun sql(statement: String): Long = dataSource.connection.use { c -> c.createStatement().use { st -> st.executeQuery(statement).use { rs -> rs.next(); rs.getLong(1) } } }
        fun member(): Member = members.save(Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "merge-${System.nanoTime()}", null)))
        fun batch(owner: Member, count: Int): UUID {
            val photos = (1..count).map {
                mapOf("photoId" to UUID.randomUUID().toString(), "imagePath" to uploads.save(UploadedImage(memberId = owner.id, path = "local/images/scans/2026/10/${UUID.randomUUID()}.jpg", contentType = "image/jpeg", sizeBytes = 10)).path)
            }
            val id = UUID.randomUUID()
            mvc.post("/api/scans/batches") {
                header("X-API-Version", "3.3")
                header("Authorization", "Bearer ${tokens.issueAccessToken(owner.id, MemberRole.USER)}")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("clientRequestId" to id.toString(), "lang" to "ko", "currency" to "KRW", "photos" to photos))
            }.andExpect { status { isOk() } }
            return id
        }
        fun succeed(owner: Member, id: UUID, index: Int, lines: List<MenuBoardLine>) = batches.recordSuccess(batches.startAttempt(owner.id, id, index)!!, lines) shouldBe true
        fun items(owner: Member, id: UUID): JsonNode = mapper.readTree(
            mvc.get("/api/scans/batches/$id") {
                header("X-API-Version", "3.3")
                header("Authorization", "Bearer ${tokens.issueAccessToken(owner.id, MemberRole.USER)}")
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload").path("items")
        fun sources(item: JsonNode) = item.path("sources").map { it.path("index").asInt() to it.path("rowIndex").asInt() }

        beforeEach {
            TestTables.clearAll(dataSource)
            batchSwitch.enabled = true
            batchSwitch.executionEnabled = false
        }
        afterSpec {
            batchSwitch.enabled = false
            batchSwitch.executionEnabled = true
            TestTables.clearAll(dataSource)
        }

        given("여러 장 결과의 조건부 병합") {
            `when`("원문 이름·가격·옵션이 모두 같은 행만 있으면") {
                then("하나로 합쳐지고 두 사진 출처가 모두 남으며, 가격·옵션·원문이 다르거나 가격이 없으면 따로 남는다") {
                    val owner = member()
                    val id = batch(owner, 2)
                    succeed(owner, id, 0, listOf(
                        MenuBoardLine("김치찌개", "김치찌개", 9000, null),
                        MenuBoardLine("불고기 (2인분)", "불고기", 15000, "2인분"),
                    ))
                    val firstId = items(owner, id)[0].path("mergedItemId").asText()
                    succeed(owner, id, 1, listOf(
                        MenuBoardLine("김치찌개", "김치찌개", 9000, null),
                        MenuBoardLine("불고기 (3인분)", "불고기", 15000, "3인분"),
                        MenuBoardLine("김치찌개", "김치찌개", 10000, null),
                        MenuBoardLine("Kimchi Stew", "김치찌개", 9000, null),
                        MenuBoardLine("비빔밥", "비빔밥", null, null),
                        MenuBoardLine("비빔밥", "비빔밥", null, null),
                    ))
                    val result = items(owner, id)
                    result.map { it.path("rawName").asText() to it.path("priceKrw").asInt(-1) } shouldBe listOf(
                        "김치찌개" to 9000, "불고기 (2인분)" to 15000, "불고기 (3인분)" to 15000, "김치찌개" to 10000, "Kimchi Stew" to 9000, "비빔밥" to -1, "비빔밥" to -1,
                    )
                    sources(result[0]) shouldBe listOf(0 to 0, 1 to 0)
                    result[0].path("mergedItemId").asText() shouldBe firstId
                    result.drop(1).all { it.path("sources").size() == 1 } shouldBe true
                    result[1].path("option").asText() shouldBe "2인분"
                    sql("SELECT COUNT(*) FROM scan_occurrence") shouldBe 8
                    sql("SELECT COUNT(*) FROM scan_history WHERE member_id = ${owner.id}") shouldBe 7
                    sql("SELECT COUNT(*) FROM scan_history_source") shouldBe 7
                }
            }
            `when`("원문 이름·가격은 같고 옵션만 다르면") {
                then("합치지 않는다(옵션 없음끼리는 같음)") {
                    val owner = member()
                    val id = batch(owner, 2)
                    succeed(owner, id, 0, listOf(MenuBoardLine("짜장면", "짜장면", 7000, null), MenuBoardLine("짜장면", "짜장면", 7000, "곱빼기")))
                    succeed(owner, id, 1, listOf(MenuBoardLine("짜장면", "짜장면", 7000, null)))
                    val result = items(owner, id)
                    result.map { it.path("option").asText("") } shouldBe listOf("", "곱빼기")
                    sources(result[0]) shouldBe listOf(0 to 0, 1 to 0)
                }
            }
            `when`("공백·전각 표기만 다른 같은 원문이면") {
                then("정규화 뒤 같은 행으로 합쳐진다") {
                    val owner = member()
                    val id = batch(owner, 2)
                    succeed(owner, id, 0, listOf(MenuBoardLine("김치  찌개", "김치찌개", 9000, " 대 ")))
                    succeed(owner, id, 1, listOf(MenuBoardLine("김치 찌개", "김치찌개", 9000, "대")))
                    items(owner, id).size() shouldBe 1
                }
            }
        }

        given("병합 행의 음식 매칭(정확 매칭만)") {
            `when`("카탈로그에 READY 음식이 있는 이름과 없는 이름이 섞이면") {
                then("있는 이름은 그 음식과 매칭, 없는 이름은 미매칭·UNKNOWN 이고 유사 대체는 없다") {
                    val owner = member()
                    val food = foods.save(Food(koreanName = "된장찌개", description = "설명", contentStatus = FoodContentStatus.READY))
                    val id = batch(owner, 1)
                    succeed(owner, id, 0, listOf(MenuBoardLine("된장찌개", "된장찌개", 8000, null), MenuBoardLine("된장찌게", "된장찌게", 8000, null)))
                    val result = items(owner, id)
                    result[0].path("foodId").asLong() shouldBe food.id
                    result[0].path("matched").asBoolean() shouldBe true
                    result[1].path("matched").asBoolean() shouldBe false
                    result[1].path("riskLevel").asText() shouldBe "UNKNOWN"
                    result[1].path("substituted").isMissingNode shouldBe true
                }
            }
        }
    }
}

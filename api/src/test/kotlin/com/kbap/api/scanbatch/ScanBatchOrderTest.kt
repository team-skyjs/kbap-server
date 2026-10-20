package com.kbap.api.scanbatch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
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
import io.kotest.matchers.string.shouldEndWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

@IntegrationTest
class ScanBatchOrderTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokens: TokenIssuer
    @Autowired private lateinit var members: MemberJpaRepository
    @Autowired private lateinit var uploads: UploadedImageJpaRepository
    @Autowired private lateinit var batchSwitch: ScanBatchSwitch
    @Autowired private lateinit var batches: ScanBatchService

    private val mapper = jacksonObjectMapper()

    init {
        fun sql(statement: String): Long = dataSource.connection.use { c -> c.createStatement().use { st -> st.executeQuery(statement).use { rs -> rs.next(); rs.getLong(1) } } }
        fun member(): Member = members.save(Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "order-batch-${System.nanoTime()}", null)))
        fun token(owner: Member) = "Bearer ${tokens.issueAccessToken(owner.id, MemberRole.USER)}"
        fun json(body: String): JsonNode = mapper.readTree(body)

        data class Batch(val id: UUID, val paths: List<String>, val items: JsonNode)
        fun batchWithResults(owner: Member, failSecond: Boolean = true): Batch {
            val paths = (1..2).map { uploads.save(UploadedImage(memberId = owner.id, path = "local/images/scans/2026/10/${UUID.randomUUID()}.jpg", contentType = "image/jpeg", sizeBytes = 10)).path }
            val id = UUID.randomUUID()
            batches.createBatch(owner.id, id, "ko", "KRW", paths.map { ScanBatchPhotoInput(UUID.randomUUID(), it) })
            batches.recordSuccess(batches.startAttempt(owner.id, id, 0)!!, listOf(MenuBoardLine("김치찌개", "김치찌개", 9000, null), MenuBoardLine("냉면", "냉면", 8000, null)))
            val second = batches.startAttempt(owner.id, id, 1)!!
            if (failSecond) batches.recordFailure(second, "RECOGNITION_FAILED") else batches.recordSuccess(second, listOf(MenuBoardLine("김치찌개", "김치찌개", 9000, null)))
            return Batch(id, paths, json(mvc.get("/api/scans/batches/$id") { header("X-API-Version", "3.3"); header("Authorization", token(owner)) }.andReturn().response.getContentAsString(Charsets.UTF_8)).path("payload").path("items"))
        }
        fun itemOf(item: JsonNode, quantity: Int = 1) = mapOf(
            "foodId" to item.path("foodId").asLong(),
            "menuName" to item.path("rawName").asText(),
            "quantity" to quantity,
            "price" to item.path("priceKrw").asInt(),
            "mergedItemId" to item.path("mergedItemId").asText(),
        )
        fun order(owner: Member, body: Map<String, Any?>, expected: Int = 200): JsonNode = json(
            mvc.post("/api/orders") {
                header("X-API-Version", "3.3")
                header("Authorization", token(owner))
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(body)
            }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )

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

        given("여러 장 스캔 묶음으로 한 주문 만들기") {
            `when`("묶음 결과에서 고른 메뉴로 주문하면") {
                then("한 주문에 묶음의 모든 원본 사진(실패 사진 포함)이 선택 순서로 붙고, 항목마다 병합 행의 출처가 전부 남는다") {
                    val owner = member()
                    val batch = batchWithResults(owner, failSecond = false)
                    val orderId = order(owner, mapOf("batchId" to batch.id.toString(), "items" to listOf(itemOf(batch.items[0], quantity = 2), itemOf(batch.items[1])))).path("payload").path("orderId").asLong()
                    sql("SELECT COUNT(*) FROM scan_order WHERE order_id = $orderId") shouldBe 1
                    sql("SELECT COUNT(*) FROM order_photo WHERE order_id = $orderId") shouldBe 2
                    sql("SELECT COUNT(*) FROM order_item_source s JOIN order_item i ON i.id = s.order_item_id WHERE i.order_id = $orderId") shouldBe 3
                    val detail = json(mvc.get("/api/orders/$orderId") { header("X-API-Version", "3.3"); header("Authorization", token(owner)) }.andReturn().response.getContentAsString(Charsets.UTF_8)).path("payload")
                    detail.path("scanImageUrls").map { it.asText() }.zip(batch.paths).forEach { (url, path) -> url shouldEndWith path }
                    detail.path("scanImageUrl").asText() shouldEndWith batch.paths[0]
                    detail.path("items").map { it.path("quantity").asInt() } shouldBe listOf(2, 1)
                }
            }
            `when`("인식 실패 사진이 있는 묶음으로 주문하면") {
                then("실패 사진도 원본 사진 목록에 남는다") {
                    val owner = member()
                    val batch = batchWithResults(owner)
                    val orderId = order(owner, mapOf("batchId" to batch.id.toString(), "items" to listOf(itemOf(batch.items[0])))).path("payload").path("orderId").asLong()
                    sql("SELECT COUNT(*) FROM order_photo WHERE order_id = $orderId") shouldBe 2
                }
            }
            `when`("같은 묶음으로 다시 주문하면") {
                then("409 ORDER-003(묶음당 주문 1회)") {
                    val owner = member()
                    val batch = batchWithResults(owner)
                    order(owner, mapOf("batchId" to batch.id.toString(), "items" to listOf(itemOf(batch.items[0]))))
                    order(owner, mapOf("batchId" to batch.id.toString(), "items" to listOf(itemOf(batch.items[1]))), expected = 409).path("code").asText() shouldBe "ORDER-003"
                }
            }
            `when`("다른 묶음의 병합 행·맞지 않는 음식·병합 행 누락·사진 경로와 함께 보내면") {
                then("400 으로 막고 주문을 남기지 않는다") {
                    val owner = member()
                    val batch = batchWithResults(owner)
                    val other = batchWithResults(owner)
                    order(owner, mapOf("batchId" to batch.id.toString(), "items" to listOf(itemOf(other.items[0]))), expected = 400)
                    order(owner, mapOf("batchId" to batch.id.toString(), "items" to listOf(itemOf(batch.items[0]) + ("foodId" to batch.items[1].path("foodId").asLong()))), expected = 400)
                    order(owner, mapOf("batchId" to batch.id.toString(), "items" to listOf(itemOf(batch.items[0]) - "mergedItemId")), expected = 400)
                    order(owner, mapOf("batchId" to batch.id.toString(), "imagePath" to batch.paths[0], "items" to listOf(itemOf(batch.items[0]))), expected = 400)
                    sql("SELECT COUNT(*) FROM orders") shouldBe 0
                }
            }
            `when`("같은 병합 행을 두 항목에 쓰거나 결과 보관(7일)이 지난 묶음으로 주문하면 — 공부·Codex P2") {
                then("중복 병합 행 400, 보관 지난 묶음 410 SCAN-013 이고 주문을 남기지 않는다") {
                    val owner = member()
                    val batch = batchWithResults(owner)
                    order(owner, mapOf("batchId" to batch.id.toString(), "items" to listOf(itemOf(batch.items[0]), itemOf(batch.items[0], quantity = 2))), expected = 400)
                    sql("UPDATE scan_operation SET expires_at = expires_at - INTERVAL 8 DAY, result_expires_at = result_expires_at - INTERVAL 8 DAY WHERE request_key = UUID_TO_BIN('${batch.id}', 0)")
                    order(owner, mapOf("batchId" to batch.id.toString(), "items" to listOf(itemOf(batch.items[0]))), expected = 410).path("code").asText() shouldBe "SCAN-013"
                    sql("SELECT COUNT(*) FROM orders") shouldBe 0
                }
            }
            `when`("남의 묶음이나 결과가 없는 묶음으로 주문하면") {
                then("남의 묶음 404 SCAN-010, 결과 없는 묶음 400") {
                    val owner = member()
                    val batch = batchWithResults(owner)
                    order(member(), mapOf("batchId" to batch.id.toString(), "items" to listOf(itemOf(batch.items[0]))), expected = 404).path("code").asText() shouldBe "SCAN-010"
                    val empty = UUID.randomUUID()
                    val path = uploads.save(UploadedImage(memberId = owner.id, path = "local/images/scans/2026/10/${UUID.randomUUID()}.jpg", contentType = "image/jpeg", sizeBytes = 10)).path
                    batches.createBatch(owner.id, empty, "ko", "KRW", listOf(ScanBatchPhotoInput(UUID.randomUUID(), path)))
                    order(owner, mapOf("batchId" to empty.toString(), "items" to listOf(itemOf(batch.items[0]))), expected = 400)
                }
            }
        }
    }
}

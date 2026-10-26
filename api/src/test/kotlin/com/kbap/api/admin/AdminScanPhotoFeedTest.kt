package com.kbap.api.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.image.model.UploadedImage
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.domain.order.OrderJpaRepository
import com.kbap.common.domain.order.OrderPhotoJpaRepository
import com.kbap.common.domain.order.model.Order
import com.kbap.common.domain.order.model.OrderPhoto
import com.kbap.common.domain.scan.ScanOperationJpaRepository
import com.kbap.common.domain.scan.ScanOperationPhotoJpaRepository
import com.kbap.common.domain.scan.model.ScanOperation
import com.kbap.common.domain.scan.model.ScanOperationPhoto
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.LocalDateTime
import java.util.UUID
import javax.sql.DataSource

@IntegrationTest
class AdminScanPhotoFeedTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var members: MemberJpaRepository
    @Autowired private lateinit var orders: OrderJpaRepository
    @Autowired private lateinit var orderPhotos: OrderPhotoJpaRepository
    @Autowired private lateinit var operations: ScanOperationJpaRepository
    @Autowired private lateinit var photos: ScanOperationPhotoJpaRepository
    @Autowired private lateinit var uploads: UploadedImageJpaRepository

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun member(uid: String, country: String? = "KR", bot: Boolean = false): Member {
            val saved = members.save(Member(provider = SocialProvider.GOOGLE, providerUid = uid, email = "$uid@test.com", nickname = uid))
            execute("UPDATE member SET country_code = ${country?.let { "'$it'" } ?: "NULL"}, is_bot = ${if (bot) 1 else 0} WHERE id = ${saved.id}")
            return saved
        }
        fun single(memberId: Long, path: String): Order = orders.save(Order(memberId = memberId, imagePath = path))
        fun batch(memberId: Long, vararg paths: String, withOrder: Boolean = false): Pair<ScanOperation, List<ScanOperationPhoto>> {
            val expiry = LocalDateTime.now().plusDays(1)
            val operation = operations.save(ScanOperation(memberId = memberId, requestKey = UUID.randomUUID().toString().toByteArray().copyOf(16), expiresAt = expiry, resultExpiresAt = expiry, keyRetireAfter = expiry))
            val saved = paths.mapIndexed { index, path ->
                val upload = uploads.save(UploadedImage(memberId = memberId, path = path, contentType = "image/jpeg", sizeBytes = 1))
                photos.save(ScanOperationPhoto(operationId = operation.id, objectKey = path, byteSize = 1, uploadedImageId = upload.id, selectionIndex = index, photoId = UUID.randomUUID().toString().toByteArray().copyOf(16)))
            }
            if (withOrder) {
                val order = orders.save(Order(memberId = memberId, imagePath = paths.first()))
                orderPhotos.saveAll(saved.map { OrderPhoto(order.id, it.id, it.selectionIndex, it.objectKey) })
            }
            return operation to saved
        }
        fun feed(query: String, role: MemberRole = MemberRole.ADMIN, expected: Int = 200): JsonNode = mapper.readTree(
            mockMvc.get("/api/admin/scan-photos$query") {
                header("X-API-Version", "3.2")
                header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, role)}")
            }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")
        fun all(query: String): List<JsonNode> {
            val items = mutableListOf<JsonNode>()
            var cursor: String? = null
            do {
                val page = feed(query + (cursor?.let { "&cursor=$it" } ?: ""))
                items += page.path("items").toList()
                cursor = page.path("nextCursor").takeIf { !it.isNull && !it.isMissingNode }?.asText()
            } while (page.path("hasNext").asBoolean())
            return items
        }

        beforeEach { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("어드민 전체 스캔 사진 피드 — GET /api/admin/scan-photos (KB-879)") {
            `when`("단일 스캔·묶음 스캔·묶음 주문·봇·탈퇴 회원 사진이 섞여 있으면") {
                then("봇은 기본 제외, 묶음 주문의 대표 사진은 BATCH 로만(출처 행 단위), 항목에 회원 정보·kind·sourceId 가 실린다") {
                    val alice = member("alice")
                    val gone = member("gone", country = null)
                    val bot = member("bot", bot = true)
                    val single = single(alice.id, "scan/a-single.jpg")
                    val (operation, _) = batch(alice.id, "scan/a-batch-1.jpg", "scan/a-batch-2.jpg", withOrder = true)
                    single(gone.id, "scan/gone.jpg")
                    execute("UPDATE member SET status = 'DELETED' WHERE id = ${gone.id}")
                    single(bot.id, "scan/bot.jpg")

                    val items = all("?")
                    items.map { it.path("imageUrl").asText().substringAfterLast('/') }.toSet() shouldBe setOf("a-single.jpg", "a-batch-1.jpg", "a-batch-2.jpg", "gone.jpg")
                    val first = items.single { it.path("imageUrl").asText().endsWith("a-single.jpg") }
                    first.path("kind").asText() shouldBe "ORDER"
                    first.path("sourceId").asLong() shouldBe single.id
                    first.path("memberId").asLong() shouldBe alice.id
                    first.path("nickname").asText() shouldBe "alice"
                    first.path("countryCode").asText() shouldBe "KR"
                    first.path("isBot").asBoolean() shouldBe false
                    first.path("memberStatus").asText() shouldBe "ACTIVE"
                    items.filter { it.path("kind").asText() == "BATCH" }.map { it.path("sourceId").asLong() }.toSet() shouldBe setOf(operation.id)
                    items.single { it.path("imageUrl").asText().endsWith("gone.jpg") }.path("memberStatus").asText() shouldBe "DELETED"
                }
                then("봇 제외 + 봇만 = 두 출처의 활성 행 수 합이고, memberId 로 한 회원만 거른다") {
                    val alice = member("alice2")
                    val bob = member("bob2")
                    val bot = member("bot2", bot = true)
                    single(alice.id, "scan/x1.jpg")
                    single(bob.id, "scan/x2.jpg")
                    single(bot.id, "scan/x3.jpg")
                    batch(alice.id, "scan/x4.jpg", "scan/x5.jpg", withOrder = true)
                    batch(bot.id, "scan/x6.jpg")
                    val activeRows = dataSource.connection.use { c ->
                        c.createStatement().use { s ->
                            s.executeQuery(
                                "SELECT (SELECT COUNT(*) FROM orders o WHERE o.status = 'ACTIVE' AND o.image_path <> '' AND NOT EXISTS (SELECT 1 FROM order_photo p WHERE p.order_id = o.id)) + (SELECT COUNT(*) FROM scan_operation_photo WHERE status = 'ACTIVE')",
                            ).use { rs -> rs.next(); rs.getLong(1) }
                        }
                    }

                    (all("?").size + all("?isBot=true").size).toLong() shouldBe activeRows
                    all("?memberId=${alice.id}").map { it.path("memberId").asLong() }.toSet() shouldBe setOf(alice.id)
                    all("?memberId=${alice.id}").size shouldBe 3
                }
            }
            `when`("같은 createdAt 사진이 페이지 경계에 걸치면") {
                then("커서로 끝까지 넘겨도 중복·누락이 없다") {
                    val alice = member("tie")
                    repeat(30) { single(alice.id, "scan/tie-o-$it.jpg") }
                    batch(alice.id, *Array(10) { "scan/tie-b-$it.jpg" })
                    execute("UPDATE orders SET created_at = '2026-10-27 12:00:00.000000'")
                    execute("UPDATE scan_operation_photo SET created_at = '2026-10-27 12:00:00.000000'")

                    val first = feed("?")
                    first.path("items").size() shouldBe 24
                    first.path("hasNext").asBoolean() shouldBe true
                    val items = all("?")
                    items.size shouldBe 40
                    items.map { it.path("imageUrl").asText() }.toSet().size shouldBe 40
                }
            }
            `when`("USER 가 부르면") {
                then("403") { feed("?", MemberRole.USER, expected = 403) }
            }
            `when`("api-docs 를 보면") {
                then("경로와 AdminScanPhotoFeed(items·nextCursor·hasNext)·항목 필드가 실린다") {
                    val docs = mapper.readTree(mockMvc.get("/v3/api-docs").andReturn().response.getContentAsString(Charsets.UTF_8))
                    docs.path("paths").has("/api/admin/scan-photos") shouldBe true
                    val schemas = docs.path("components").path("schemas")
                    schemas.path("AdminScanPhotoFeed").path("properties").fieldNames().asSequence().toSet() shouldBe setOf("items", "nextCursor", "hasNext")
                    schemas.path("AdminScanPhotoFeedItem").path("properties").fieldNames().asSequence().toSet() shouldBe
                        setOf("imageUrl", "createdAt", "memberId", "nickname", "countryCode", "isBot", "memberStatus", "kind", "sourceId")
                }
            }
        }
    }
}

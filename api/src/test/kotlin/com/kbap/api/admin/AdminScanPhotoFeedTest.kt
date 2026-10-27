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
        fun upload(memberId: Long, path: String): UploadedImage =
            uploads.save(UploadedImage(memberId = memberId, path = "dev/images/scans/2026/10/$path", contentType = "image/jpeg", sizeBytes = 1))
        fun single(memberId: Long, path: String): Order = orders.save(Order(memberId = memberId, imagePath = upload(memberId, path).path))
        fun batch(memberId: Long, vararg paths: String, withOrder: Boolean = false): Pair<ScanOperation, List<ScanOperationPhoto>> {
            val expiry = LocalDateTime.now().plusDays(1)
            val operation = operations.save(ScanOperation(memberId = memberId, requestKey = UUID.randomUUID().toString().toByteArray().copyOf(16), expiresAt = expiry, resultExpiresAt = expiry, keyRetireAfter = expiry))
            val saved = paths.mapIndexed { index, path ->
                val upload = upload(memberId, path)
                photos.save(ScanOperationPhoto(operationId = operation.id, objectKey = upload.path, byteSize = 1, uploadedImageId = upload.id, selectionIndex = index, photoId = UUID.randomUUID().toString().toByteArray().copyOf(16)))
            }
            if (withOrder) {
                val order = orders.save(Order(memberId = memberId, imagePath = saved.first().objectKey))
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

        given("어드민 전체 스캔 사진 피드 — GET /api/admin/scan-photos (KB-888 업로드 기록 소스)") {
            `when`("단일 스캔·묶음 스캔·묶음 주문·카드 미저장 스캔·봇·탈퇴 회원·다른 용도 업로드가 섞여 있으면") {
                then("스캔 업로드 한 장당 1건 — 주문 사진은 ORDER, 묶음만이면 BATCH, 카드 미저장은 SCAN_ONLY, 봇·다른 용도·삭제 업로드는 빠진다") {
                    val alice = member("alice")
                    val gone = member("gone", country = null)
                    val bot = member("bot", bot = true)
                    val single = single(alice.id, "a-single.jpg")
                    val (operation, _) = batch(alice.id, "a-batch-1.jpg", "a-batch-2.jpg", withOrder = true)
                    val batchOrder = orders.findAll().single { it.imagePath!!.endsWith("a-batch-1.jpg") }
                    upload(alice.id, "a-scan-only.jpg")
                    upload(alice.id, "a-removed.jpg").also { execute("UPDATE uploaded_image SET status = 'DELETED' WHERE id = ${it.id}") }
                    uploads.save(UploadedImage(memberId = alice.id, path = "dev/images/review/2026/10/a-review.jpg", contentType = "image/jpeg", sizeBytes = 1))
                    single(gone.id, "gone.jpg")
                    execute("UPDATE member SET status = 'DELETED' WHERE id = ${gone.id}")
                    single(bot.id, "bot.jpg")

                    val items = all("?")
                    val byFile = items.associateBy { it.path("imageUrl").asText().substringAfterLast('/') }
                    items.size shouldBe byFile.size
                    byFile.keys shouldBe setOf("a-single.jpg", "a-batch-1.jpg", "a-batch-2.jpg", "a-scan-only.jpg", "gone.jpg")
                    val first = byFile.getValue("a-single.jpg")
                    first.path("kind").asText() shouldBe "ORDER"
                    first.path("sourceId").asLong() shouldBe single.id
                    first.path("memberId").asLong() shouldBe alice.id
                    first.path("nickname").asText() shouldBe "alice"
                    first.path("countryCode").asText() shouldBe "KR"
                    first.path("isBot").asBoolean() shouldBe false
                    first.path("memberStatus").asText() shouldBe "ACTIVE"
                    byFile.getValue("a-batch-1.jpg").path("kind").asText() shouldBe "ORDER"
                    byFile.getValue("a-batch-1.jpg").path("sourceId").asLong() shouldBe batchOrder.id
                    byFile.getValue("a-batch-2.jpg").path("kind").asText() shouldBe "BATCH"
                    byFile.getValue("a-batch-2.jpg").path("sourceId").asLong() shouldBe operation.id
                    byFile.getValue("a-scan-only.jpg").path("kind").asText() shouldBe "SCAN_ONLY"
                    byFile.getValue("a-scan-only.jpg").path("sourceId").isNull shouldBe true
                    byFile.getValue("gone.jpg").path("memberStatus").asText() shouldBe "DELETED"
                }
                then("봇 제외 + 봇만 = 스캔 용도 ACTIVE 업로드 행 수이고, memberId 로 한 회원만 거른다") {
                    val alice = member("alice2")
                    val bob = member("bob2")
                    val bot = member("bot2", bot = true)
                    single(alice.id, "x1.jpg")
                    single(bob.id, "x2.jpg")
                    single(bot.id, "x3.jpg")
                    batch(alice.id, "x4.jpg", "x5.jpg", withOrder = true)
                    batch(bot.id, "x6.jpg")
                    upload(bob.id, "x7.jpg")
                    val scanUploads = dataSource.connection.use { c ->
                        c.createStatement().use { s ->
                            s.executeQuery("SELECT COUNT(*) FROM uploaded_image WHERE status = 'ACTIVE' AND object_path LIKE '%/images/scans/%'")
                                .use { rs -> rs.next(); rs.getLong(1) }
                        }
                    }

                    (all("?").size + all("?isBot=true").size).toLong() shouldBe scanUploads
                    all("?memberId=${alice.id}").map { it.path("memberId").asLong() }.toSet() shouldBe setOf(alice.id)
                    all("?memberId=${alice.id}").size shouldBe 3
                }
                then("excludeMemberIds 로 준 회원들의 사진은 0건이다") {
                    val keep = member("keep")
                    val drop1 = member("drop1")
                    val drop2 = member("drop2")
                    single(keep.id, "k1.jpg")
                    upload(drop1.id, "d1.jpg")
                    batch(drop2.id, "d2.jpg")

                    all("?excludeMemberIds=${drop1.id},${drop2.id}").map { it.path("memberId").asLong() }.toSet() shouldBe setOf(keep.id)
                }
            }
            `when`("같은 createdAt 사진이 페이지 경계에 걸치면") {
                then("커서로 끝까지 넘겨도 중복·누락이 없다") {
                    val alice = member("tie")
                    repeat(20) { single(alice.id, "tie-o-$it.jpg") }
                    batch(alice.id, *Array(10) { "tie-b-$it.jpg" })
                    repeat(30) { upload(alice.id, "tie-s-$it.jpg") }
                    execute("UPDATE uploaded_image SET created_at = '2026-10-27 12:00:00.000000'")

                    val first = feed("?")
                    first.path("items").size() shouldBe 24
                    first.path("hasNext").asBoolean() shouldBe true
                    val items = all("?")
                    items.size shouldBe 60
                    items.map { it.path("imageUrl").asText() }.toSet().size shouldBe 60
                }
            }
            `when`("해석할 수 없는 커서를 주면") {
                then("400") { feed("?cursor=not-a-cursor", expected = 400) }
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
                    schemas.path("AdminScanPhotoFeedItem").path("properties").path("kind").path("enum").map { it.asText() } shouldBe
                        listOf("ORDER", "BATCH", "SCAN_ONLY")
                }
            }
        }
    }
}

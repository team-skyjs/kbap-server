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
import com.kbap.common.domain.order.model.Order
import com.kbap.common.domain.scan.ScanOperationJpaRepository
import com.kbap.common.domain.scan.ScanOperationPhotoJpaRepository
import com.kbap.common.domain.scan.ScanUsageJpaRepository
import com.kbap.common.domain.scan.model.ScanOperation
import com.kbap.common.domain.scan.model.ScanOperationPhoto
import com.kbap.common.domain.scan.model.ScanUsage
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.LocalDateTime
import java.util.UUID
import javax.sql.DataSource

@IntegrationTest
class AdminPackagedScanPhotoAndCountryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var members: MemberJpaRepository
    @Autowired private lateinit var orders: OrderJpaRepository
    @Autowired private lateinit var operations: ScanOperationJpaRepository
    @Autowired private lateinit var photos: ScanOperationPhotoJpaRepository
    @Autowired private lateinit var usages: ScanUsageJpaRepository
    @Autowired private lateinit var uploads: UploadedImageJpaRepository

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun long(sql: String): Long = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } } }
        fun member(uid: String, country: String? = "KR", bot: Boolean = false): Member {
            val saved = members.save(Member(provider = SocialProvider.GOOGLE, providerUid = uid, email = "$uid@test.com", nickname = uid))
            execute("UPDATE member SET country_code = ${country?.let { "'$it'" } ?: "NULL"}, is_bot = ${if (bot) 1 else 0} WHERE id = ${saved.id}")
            return saved
        }
        fun upload(memberId: Long, path: String): UploadedImage =
            uploads.save(UploadedImage(memberId = memberId, path = path, contentType = "image/jpeg", sizeBytes = 1))
        fun get(path: String, expected: Int = 200): JsonNode = mapper.readTree(
            mockMvc.get(path) {
                header("X-API-Version", "3.2")
                header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
            }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        fun post(path: String, body: Map<String, Any?>, memberId: Long): JsonNode = mapper.readTree(
            mockMvc.post(path) {
                header("X-API-Version", "3.2")
                header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(memberId, MemberRole.USER)}")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(body)
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        fun operation(memberId: Long, namespace: String): ScanOperation {
            val expiry = LocalDateTime.now().plusDays(1)
            return operations.save(ScanOperation(memberId = memberId, keyNamespace = namespace, requestKey = UUID.randomUUID().toString().toByteArray().copyOf(16), expiresAt = expiry, resultExpiresAt = expiry, keyRetireAfter = expiry))
        }
        fun used(memberId: Long, namespace: String, at: LocalDateTime = LocalDateTime.now()) {
            val op = operation(memberId, namespace)
            usages.save(ScanUsage(op.id, at, "FREE"))
            if (namespace == "SCAN_BATCH") {
                val photo = upload(memberId, "dev/images/scans/2026/11/batch-${op.id}.jpg")
                photos.save(ScanOperationPhoto(operationId = op.id, objectKey = photo.path, byteSize = 1, uploadedImageId = photo.id, selectionIndex = 0, photoId = UUID.randomUUID().toString().toByteArray().copyOf(16)))
            }
        }

        beforeSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, allergens, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES ('SP-1', '새우깡', '새우깡', '과자', '농심', '소맥분(밀), 새우', '밀, 새우', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
            )
        }
        afterSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }

        given("스캔 사진 피드에 가공식품 업로드 포함(KB-964)") {
            val owner = member("pf-photo-owner")
            val menu = upload(owner.id, "dev/images/scans/2026/11/menu.jpg")
            orders.save(Order(memberId = owner.id, imagePath = menu.path))
            val linked = upload(owner.id, "dev/images/packaged-scans/2026/11/front.jpg")
            upload(owner.id, "dev/images/packaged-scans/2026/11/label.jpg")
            val analysisId = post("/api/processed-foods/scan/name", mapOf("ocrText" to "새우깡", "imagePath" to linked.path), owner.id).path("payload").path("analysisId").asText()
            post("/api/processed-foods/scan/confirm", mapOf("analysisId" to analysisId, "reportNo" to "SP-1"), owner.id)
            val productId = long("SELECT id FROM processed_food_product WHERE report_no = 'SP-1'")

            `when`("type 없이(ALL) 전체 피드를 보면") {
                then("메뉴판·가공식품 사진이 함께 나오고, 가공식품은 kind=SCAN_ONLY·sourceId=null 에 scanType=PACKAGED, 확정 연결된 사진만 processedFoodId·이름이 있다") {
                    val items = get("/api/admin/scan-photos?memberId=${owner.id}").path("payload").path("items").associateBy { it.path("imageUrl").asText().substringAfterLast('/') }
                    items.keys shouldBe setOf("menu.jpg", "front.jpg", "label.jpg")
                    items.getValue("menu.jpg").path("scanType").asText() shouldBe "MENU"
                    items.getValue("menu.jpg").path("kind").asText() shouldBe "ORDER"
                    items.getValue("front.jpg").path("scanType").asText() shouldBe "PACKAGED"
                    items.getValue("front.jpg").path("kind").asText() shouldBe "SCAN_ONLY"
                    items.getValue("front.jpg").path("sourceId").isNull shouldBe true
                    items.getValue("front.jpg").path("processedFoodId").asLong() shouldBe productId
                    items.getValue("front.jpg").path("processedFoodName").asText() shouldBe "새우깡"
                    items.getValue("label.jpg").path("processedFoodId").isNull shouldBe true
                    items.getValue("label.jpg").path("processedFoodName").isNull shouldBe true
                    items.getValue("menu.jpg").path("processedFoodId").isNull shouldBe true
                }
            }
            `when`("type=MENU·PACKAGED 로 거르면") {
                then("전체 피드와 회원별 스캔 사진 둘 다 그 종류만 나오고, 잘못된 type 은 400") {
                    fun names(path: String) = get(path).path("payload").path("items").map { it.path("imageUrl").asText().substringAfterLast('/') }.toSet()
                    names("/api/admin/scan-photos?memberId=${owner.id}&type=MENU") shouldBe setOf("menu.jpg")
                    names("/api/admin/scan-photos?memberId=${owner.id}&type=PACKAGED") shouldBe setOf("front.jpg", "label.jpg")
                    names("/api/admin/members/${owner.id}/scan-photos") shouldBe setOf("menu.jpg", "front.jpg", "label.jpg")
                    names("/api/admin/members/${owner.id}/scan-photos?type=MENU") shouldBe setOf("menu.jpg")
                    names("/api/admin/members/${owner.id}/scan-photos?type=PACKAGED") shouldBe setOf("front.jpg", "label.jpg")
                    val gallery = get("/api/admin/members/${owner.id}/scan-photos?type=PACKAGED").path("payload").path("items").associateBy { it.path("imageUrl").asText().substringAfterLast('/') }
                    gallery.getValue("front.jpg").path("scanType").asText() shouldBe "PACKAGED"
                    gallery.getValue("front.jpg").path("processedFoodName").asText() shouldBe "새우깡"
                    get("/api/admin/scan-photos?type=OTHER", expected = 400)
                    get("/api/admin/members/${owner.id}/scan-photos?type=OTHER", expected = 400)
                }
            }
        }

        given("대시보드 국가별 스캔 횟수(KB-964)") {
            `when`("기간·봇·제외 회원을 섞어 집계하면") {
                then("픽스처를 손으로 센 값과 같고, total 내림차순·국가 미설정은 null 한 줄이다") {
                    TestTables.clearAll(dataSource)
                    val kr1 = member("c-kr1", "KR")
                    val kr2 = member("c-kr2", "KR")
                    val us = member("c-us", "US")
                    val none = member("c-none", null)
                    val bot = member("c-bot", "KR", bot = true)
                    val excluded = member("c-excluded", "JP")
                    repeat(2) { used(kr1.id, "SCAN_BATCH") }
                    used(kr1.id, "PFOOD_NAME")
                    upload(kr1.id, "dev/images/scans/2026/11/v1-single.jpg")
                    upload(kr1.id, "dev/images/packaged-scans/2026/11/no-usage.jpg")
                    used(kr2.id, "PFOOD_LABEL")
                    used(us.id, "SCAN_BATCH")
                    used(us.id, "SCAN_BATCH", LocalDateTime.now().minusDays(40))
                    used(none.id, "PFOOD_NAME")
                    repeat(3) { used(bot.id, "SCAN_BATCH") }
                    repeat(2) { used(excluded.id, "SCAN_BATCH") }

                    fun rows(query: String) = get("/api/admin/dashboard/scan-countries$query").path("payload")
                    fun table(payload: JsonNode) = payload.path("rows").map {
                        listOf(it.path("countryCode").takeUnless(JsonNode::isNull)?.asText(), it.path("menuCount").asLong(), it.path("packagedCount").asLong(), it.path("total").asLong())
                    }
                    val month = rows("?days=30&excludeMemberIds=${excluded.id}")
                    table(month) shouldBe listOf(listOf("KR", 3L, 2L, 5L), listOf("US", 1L, 0L, 1L), listOf(null, 0L, 1L, 1L))
                    month.path("totals").path("menuCount").asLong() shouldBe 4
                    month.path("totals").path("packagedCount").asLong() shouldBe 3
                    month.path("totals").path("total").asLong() shouldBe 7
                    month.path("from").asText() shouldBe java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(29).toString()
                    month.path("to").asText() shouldBe java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString()

                    val all = rows("?excludeMemberIds=${excluded.id}")
                    table(all) shouldBe listOf(listOf("KR", 3L, 2L, 5L), listOf("US", 2L, 0L, 2L), listOf(null, 0L, 1L, 1L))
                    all.path("from").isNull shouldBe true

                    table(rows("?days=7")).first() shouldBe listOf("KR", 3L, 2L, 5L)
                    table(rows("?days=7")).any { it.first() == "JP" } shouldBe true
                    get("/api/admin/dashboard/scan-countries?days=14", expected = 400)
                }
            }
        }
    }
}

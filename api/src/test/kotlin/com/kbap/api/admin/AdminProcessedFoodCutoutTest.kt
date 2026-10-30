package com.kbap.api.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.image.FakeStorageObjectStore
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import javax.sql.DataSource

@IntegrationTest
class AdminProcessedFoodCutoutTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var storage: FakeStorageObjectStore

    private val mapper = jacksonObjectMapper()

    init {
        val reportNo = "PF-CUT-1"
        val key = "local/processed/cutout/$reportNo.png"
        val haccpImage = "http://www.haccp.or.kr/fresh/prdimg/cut/1.jpg"
        val modifiedAt = Instant.parse("2026-10-30T04:00:00Z")
        val cutoutUrl = "https://cdn.test/$key?v=${modifiedAt.epochSecond}"
        var adminId = 0L

        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

        fun cutoutColumn(id: Long): Pair<String?, String?> = dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT cutout_image_url, image_url FROM processed_food_product WHERE id = $id").use { rs -> rs.next(); rs.getString(1) to rs.getString(2) }
            }
        }

        fun reviewColumns(id: Long): Pair<String?, String?> = dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT cutout_review_status, cutout_reviewed_by FROM processed_food_product WHERE id = $id").use { rs -> rs.next(); rs.getString(1) to rs.getString(2) }
            }
        }

        fun seedProduct(): Long {
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, image_url, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES ('$reportNo', '누끼칩', '누끼칩', '과자', '$haccpImage', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
            )
            return dataSource.connection.use { c -> c.createStatement().use { st -> st.executeQuery("SELECT id FROM processed_food_product WHERE report_no = '$reportNo'").use { rs -> rs.next(); rs.getLong(1) } } }
        }

        fun admin(request: org.springframework.test.web.servlet.MockHttpServletRequestDsl, body: Any? = null) {
            request.header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(adminId, MemberRole.ADMIN)}")
            request.header("X-API-Version", "1.0")
            body?.let {
                request.contentType = MediaType.APPLICATION_JSON
                request.content = mapper.writeValueAsString(it)
            }
        }

        fun read(result: org.springframework.test.web.servlet.ResultActionsDsl, expected: Int): JsonNode =
            mapper.readTree(result.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8))

        fun presign(id: Long, contentLength: Long, expected: Int = 200) =
            read(mockMvc.post("/api/admin/processed-foods/$id/cutout-image/presign") { admin(this, mapOf("contentLength" to contentLength)) }, expected)

        fun confirm(id: Long, body: String, expected: Int = 200) =
            read(mockMvc.post("/api/admin/processed-foods/$id/cutout-image/confirm") { admin(this, mapOf("key" to body)) }, expected)

        fun remove(id: Long, expected: Int = 200) =
            read(mockMvc.delete("/api/admin/processed-foods/$id/cutout-image") { admin(this) }, expected)

        fun appGet(path: String): JsonNode =
            read(mockMvc.get(path) { header("X-API-Version", "3.2") }, 200)

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            storage.heads.remove(key)
            execute("INSERT IGNORE INTO admin_account (admin_id, admin_pwd, status, created_at, updated_at) VALUES ('cutout-uploader', 'x', 'ACTIVE', NOW(6), NOW(6))")
            adminId = dataSource.connection.use { c -> c.createStatement().use { st -> st.executeQuery("SELECT id FROM admin_account WHERE admin_id = 'cutout-uploader'").use { rs -> rs.next(); rs.getLong(1) } } }
        }
        afterSpec { execute("DELETE FROM processed_food_product") }

        given("가공식품 누끼 이미지 업로드 URL 발급") {
            `when`("PNG 크기를 주면") {
                then("품목보고번호 고정 키로 10분 PUT URL 을 준다") {
                    val id = seedProduct()

                    val payload = presign(id, 183_204).path("payload")

                    payload.path("key").asText() shouldBe key
                    payload.path("publicUrl").asText() shouldBe "https://cdn.test/$key"
                    payload.path("requiredHeaders").path("Content-Type").asText() shouldBe "image/png"
                    payload.path("requiredHeaders").path("Content-Length").asText() shouldBe "183204"
                }
            }

            `when`("2MB 를 넘거나 없는 제품이면") {
                then("400 UPLOAD-003 · 404 PFOOD-001 이다") {
                    val id = seedProduct()
                    presign(id, 2L * 1024 * 1024 + 1, 400).path("code").asText() shouldBe "UPLOAD-003"
                    presign(Long.MAX_VALUE, 10, 404).path("code").asText() shouldBe "PFOOD-001"
                }
            }
        }

        given("가공식품 누끼 이미지 저장 — confirm") {
            `when`("오브젝트가 없거나 PNG 가 아니거나 2MB 를 넘으면") {
                then("400 PFOOD-008 이고 아무것도 저장하지 않는다") {
                    val id = seedProduct()
                    confirm(id, key, 400).path("code").asText() shouldBe "PFOOD-008"

                    storage.stub(key, "image/jpeg", 1000, modifiedAt)
                    confirm(id, key, 400).path("code").asText() shouldBe "PFOOD-008"

                    storage.stub(key, "image/png", 2L * 1024 * 1024 + 1, modifiedAt)
                    confirm(id, key, 400).path("code").asText() shouldBe "PFOOD-008"

                    cutoutColumn(id) shouldBe (null to haccpImage)
                }
            }

            `when`("이 제품의 키가 아니면") {
                then("400 COMMON-002 다") {
                    val id = seedProduct()
                    storage.stub("local/processed/cutout/OTHER.png", "image/png", 1000, modifiedAt)
                    confirm(id, "local/processed/cutout/OTHER.png", 400).path("code").asText() shouldBe "COMMON-002"
                    cutoutColumn(id).first shouldBe null
                }
            }

            `when`("PUT 된 PNG 가 있으면") {
                then("누끼 URL 을 승인 상태로 저장하고 목록·상세 imageUrl 이 누끼를 우선하며 HACCP 원본·출처 표기는 그대로다 — 다시 확인해도 같다") {
                    val id = seedProduct()
                    storage.stub(key, "image/png", 183_204, modifiedAt)

                    val saved = confirm(id, key).path("payload")
                    saved.path("cutoutImageUrl").asText() shouldBe cutoutUrl
                    saved.path("imageUrl").asText() shouldBe cutoutUrl
                    confirm(id, key).path("payload").path("cutoutImageUrl").asText() shouldBe cutoutUrl

                    cutoutColumn(id) shouldBe (cutoutUrl to haccpImage)
                    reviewColumns(id) shouldBe ("APPROVED" to adminId.toString())
                    appGet("/api/processed-foods?query=누끼칩").path("payload").path("items").single().path("imageUrl").asText() shouldBe cutoutUrl
                    appGet("/api/processed-foods/$id").path("payload").path("image").let {
                        it.path("url").asText() shouldBe cutoutUrl
                        it.path("attributionKey").asText() shouldBe "processedFood.image.haccp"
                    }
                }
            }
        }

        given("가공식품 누끼 이미지 해제 — DELETE") {
            `when`("누끼가 있던 제품을 해제하면") {
                then("imageUrl 이 HACCP 원본(https)으로 돌아가고, 다시 불러도 200 이다") {
                    val id = seedProduct()
                    storage.stub(key, "image/png", 1000, modifiedAt)
                    confirm(id, key)

                    remove(id).path("payload").let {
                        it.path("cutoutImageUrl").isNull shouldBe true
                        it.path("imageUrl").asText() shouldBe "https://www.haccp.or.kr/fresh/prdimg/cut/1.jpg"
                    }
                    remove(id)

                    cutoutColumn(id) shouldBe (null to haccpImage)
                    reviewColumns(id) shouldBe (null to null)
                    appGet("/api/processed-foods/$id").path("payload").path("image").path("url").asText() shouldBe "https://www.haccp.or.kr/fresh/prdimg/cut/1.jpg"
                }
            }
        }
    }
}

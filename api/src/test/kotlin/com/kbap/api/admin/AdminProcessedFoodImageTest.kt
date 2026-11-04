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
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.security.MessageDigest
import javax.sql.DataSource

@IntegrationTest
class AdminProcessedFoodImageTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var storage: FakeStorageObjectStore

    private val mapper = jacksonObjectMapper()

    init {
        val reportNo = "PF-IMG-1"
        val haccpImage = "http://www.haccp.or.kr/fresh/prdimg/img/1.jpg"
        val haccpSecure = "https://www.haccp.or.kr/fresh/prdimg/img/1.jpg"
        var adminId = 0L

        fun sha256Of(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        fun keyOf(sha256: String, ext: String) = "local/processed/images/$reportNo-${sha256.take(16)}.$ext"
        fun urlOf(key: String) = "https://cdn.test/$key"

        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun longOf(sql: String): Long = dataSource.connection.use { c -> c.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } } }

        fun seedProduct(no: String = reportNo, name: String = "갤러리라면"): Long {
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, image_url, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES ('$no', '$name', '$name', '유탕면', '$haccpImage', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
            )
            return longOf("SELECT id FROM processed_food_product WHERE report_no = '$no'")
        }

        fun admin(request: MockHttpServletRequestDsl, body: Any? = null) {
            request.header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(adminId, MemberRole.ADMIN)}")
            request.header("X-API-Version", "1.0")
            body?.let {
                request.contentType = MediaType.APPLICATION_JSON
                request.content = mapper.writeValueAsString(it)
            }
        }

        fun read(result: ResultActionsDsl, expected: Int): JsonNode =
            mapper.readTree(result.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8))

        val base = "/api/admin/processed-foods"

        fun presign(id: Long, contentType: String, contentLength: Long, sha256: String, expected: Int = 200) =
            read(mockMvc.post("$base/$id/images/presign") { admin(this, mapOf("contentType" to contentType, "contentLength" to contentLength, "contentSha256" to sha256)) }, expected)

        fun confirm(id: Long, key: String, kind: String, expected: Int = 200) =
            read(mockMvc.post("$base/$id/images/confirm") { admin(this, mapOf("key" to key, "kind" to kind)) }, expected)

        fun reorder(id: Long, imageIds: List<Long>, expected: Int = 200) =
            read(mockMvc.put("$base/$id/images/order") { admin(this, mapOf("imageIds" to imageIds)) }, expected)

        fun remove(id: Long, imageId: Long, expected: Int = 200) =
            read(mockMvc.delete("$base/$id/images/$imageId") { admin(this) }, expected)

        fun list(id: Long) = read(mockMvc.get("$base/$id/images") { admin(this) }, 200).path("payload").path("images")

        fun appDetail(id: Long): JsonNode = read(mockMvc.get("/api/processed-foods/$id") { header("X-API-Version", "3.2") }, 200).path("payload")

        fun upload(id: Long, name: String, kind: String, contentType: String = "image/jpeg", ext: String = "jpg"): String {
            val sha256 = sha256Of(name)
            val key = keyOf(sha256, ext)
            storage.stub(key, contentType, 300_000, sha256Hex = sha256)
            confirm(id, key, kind)
            return urlOf(key)
        }

        fun pairs(images: JsonNode) = images.map { it.path("url").asText() to it.path("kind").asText() }

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            storage.heads.keys.filter { it.startsWith("local/processed/images/") }.forEach { storage.heads.remove(it) }
            execute("INSERT IGNORE INTO admin_account (admin_id, admin_pwd, status, created_at, updated_at) VALUES ('image-uploader', 'x', 'ACTIVE', NOW(6), NOW(6))")
            adminId = longOf("SELECT id FROM admin_account WHERE admin_id = 'image-uploader'")
        }
        afterSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }

        given("가공식품 사진 업로드 URL 발급") {
            `when`("JPEG 크기와 SHA-256 을 주면") {
                then("images 경로에 품목보고번호-해시 앞 16자 .jpg 키와 Content-Type 헤더를 준다") {
                    val id = seedProduct()
                    val sha256 = sha256Of("front")

                    val payload = presign(id, "image/jpeg", 300_000, sha256).path("payload")

                    payload.path("key").asText() shouldBe keyOf(sha256, "jpg")
                    payload.path("publicUrl").asText() shouldBe urlOf(keyOf(sha256, "jpg"))
                    payload.path("requiredHeaders").path("Content-Type").asText() shouldBe "image/jpeg"
                }
            }

            `when`("PNG·JPEG·WebP 가 아니거나 5MB 를 넘거나 없는 제품이면") {
                then("400 COMMON-002 · 400 UPLOAD-003 · 404 PFOOD-001 이다") {
                    val id = seedProduct()
                    presign(id, "image/gif", 1000, sha256Of("gif"), 400).path("code").asText() shouldBe "COMMON-002"
                    presign(id, "image/jpeg", 5L * 1024 * 1024 + 1, sha256Of("big"), 400).path("code").asText() shouldBe "UPLOAD-003"
                    presign(Long.MAX_VALUE, "image/jpeg", 1000, sha256Of("none"), 404).path("code").asText() shouldBe "PFOOD-001"
                }
            }
        }

        given("가공식품 사진 저장 — confirm") {
            `when`("오브젝트가 없거나 키 확장자와 Content-Type 이 다르거나 체크섬이 다르면") {
                then("400 PFOOD-015 이고 아무것도 저장하지 않는다") {
                    val id = seedProduct()
                    val sha256 = sha256Of("front")
                    val key = keyOf(sha256, "jpg")
                    confirm(id, key, "FRONT", 400).path("code").asText() shouldBe "PFOOD-015"
                    storage.stub(key, "image/png", 1000, sha256Hex = sha256)
                    confirm(id, key, "FRONT", 400).path("code").asText() shouldBe "PFOOD-015"
                    storage.stub(key, "image/jpeg", 1000, sha256Hex = "f".repeat(64))
                    confirm(id, key, "FRONT", 400).path("code").asText() shouldBe "PFOOD-015"

                    longOf("SELECT COUNT(*) FROM processed_food_image") shouldBe 0
                }
            }

            `when`("다른 제품의 키면") {
                then("400 COMMON-002 다") {
                    val id = seedProduct()
                    val sha256 = sha256Of("front")
                    val other = "local/processed/images/OTHER-${sha256.take(16)}.jpg"
                    storage.stub(other, "image/jpeg", 1000, sha256Hex = sha256)
                    confirm(id, other, "FRONT", 400).path("code").asText() shouldBe "COMMON-002"
                }
            }

            `when`("올린 사진을 차례로 확인하면") {
                then("올린 순서대로 뒤에 붙고, 같은 키를 다시 확인해도 한 장이다") {
                    val id = seedProduct()
                    val front = upload(id, "front", "FRONT")
                    val label = upload(id, "label", "LABEL")
                    upload(id, "front", "FRONT")

                    pairs(list(id)) shouldBe listOf(front to "FRONT", label to "LABEL")
                }
            }
        }

        given("앱 가공식품 상세 images") {
            `when`("추가 사진이 없으면") {
                then("현행 대표 사진 한 장이고 image 는 그대로다") {
                    val id = seedProduct()

                    val detail = appDetail(id)

                    pairs(detail.path("images")) shouldBe listOf(haccpSecure to "FRONT")
                    detail.path("image").path("url").asText() shouldBe haccpSecure
                }
            }

            `when`("누끼 사진이 있으면") {
                then("누끼가 먼저, 나머지는 sort_order 순이고 image 는 그대로다") {
                    val id = seedProduct()
                    val front = upload(id, "front", "FRONT")
                    val label = upload(id, "label", "LABEL")
                    val cutout = upload(id, "cutout", "CUTOUT", "image/png", "png")

                    val detail = appDetail(id)

                    pairs(detail.path("images")) shouldBe listOf(cutout to "CUTOUT", front to "FRONT", label to "LABEL")
                    detail.path("image").path("url").asText() shouldBe haccpSecure
                }
            }

            `when`("기존 누끼 컬럼이 승인돼 있고 추가 사진에 누끼가 없으면") {
                then("승인 누끼가 CUTOUT 으로 먼저다") {
                    val id = seedProduct()
                    execute("UPDATE processed_food_product SET cutout_image_url = 'https://cdn.test/c.png', cutout_review_status = 'APPROVED' WHERE id = $id")
                    val top = upload(id, "top", "TOP")

                    pairs(appDetail(id).path("images")) shouldBe listOf("https://cdn.test/c.png" to "CUTOUT", top to "TOP")
                }
            }

            `when`("누끼 사진 없이 추가 사진만 있으면") {
                then("현행 대표 사진이 먼저, 그 뒤에 추가 사진이다") {
                    val id = seedProduct()
                    val top = upload(id, "top", "TOP")

                    pairs(appDetail(id).path("images")) shouldBe listOf(haccpSecure to "FRONT", top to "TOP")
                }
            }
        }

        given("가공식품 사진 순서 변경") {
            `when`("이 제품 사진 id 전부를 새 순서로 주면") {
                then("그 순서로 저장되고 앱 상세도 따른다") {
                    val id = seedProduct()
                    val front = upload(id, "front", "FRONT")
                    val label = upload(id, "label", "LABEL")
                    val ids = list(id).map { it.path("id").asLong() }

                    pairs(reorder(id, ids.reversed()).path("payload").path("images")) shouldBe listOf(label to "LABEL", front to "FRONT")
                    pairs(appDetail(id).path("images")) shouldBe listOf(haccpSecure to "FRONT", label to "LABEL", front to "FRONT")
                }
            }

            `when`("빠진 id·남의 id·중복이 있으면") {
                then("400 COMMON-002 이고 순서는 그대로다") {
                    val id = seedProduct()
                    val other = seedProduct("PF-IMG-2", "다른라면")
                    upload(id, "front", "FRONT")
                    upload(id, "label", "LABEL")
                    val ids = list(id).map { it.path("id").asLong() }
                    val sha256 = sha256Of("x")
                    storage.stub("local/processed/images/PF-IMG-2-${sha256.take(16)}.jpg", "image/jpeg", 1000, sha256Hex = sha256)
                    confirm(other, "local/processed/images/PF-IMG-2-${sha256.take(16)}.jpg", "FRONT")
                    val othersId = list(other).single().path("id").asLong()

                    reorder(id, ids.take(1), 400).path("code").asText() shouldBe "COMMON-002"
                    reorder(id, listOf(ids[0], othersId), 400).path("code").asText() shouldBe "COMMON-002"
                    reorder(id, listOf(ids[0], ids[0]), 400).path("code").asText() shouldBe "COMMON-002"
                    list(id).map { it.path("id").asLong() } shouldBe ids
                }
            }
        }

        given("가공식품 사진 삭제") {
            `when`("이 제품 사진을 지우면") {
                then("목록과 앱 상세에서 빠지고, 다시 지우거나 남의 사진 id 면 404 PFOOD-016 이다") {
                    val id = seedProduct()
                    val other = seedProduct("PF-IMG-2", "다른라면")
                    val front = upload(id, "front", "FRONT")
                    upload(id, "label", "LABEL")
                    val labelId = list(id)[1].path("id").asLong()

                    pairs(remove(id, labelId).path("payload").path("images")) shouldBe listOf(front to "FRONT")
                    pairs(appDetail(id).path("images")) shouldBe listOf(haccpSecure to "FRONT", front to "FRONT")
                    remove(id, labelId, 404).path("code").asText() shouldBe "PFOOD-016"
                    remove(other, list(id).single().path("id").asLong(), 404).path("code").asText() shouldBe "PFOOD-016"
                }
            }
        }
    }
}

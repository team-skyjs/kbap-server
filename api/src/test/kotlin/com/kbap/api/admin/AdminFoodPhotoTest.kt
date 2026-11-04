package com.kbap.api.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.image.FakeStorageObjectStore
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
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
import org.springframework.test.web.servlet.post
import java.security.MessageDigest
import javax.sql.DataSource

@IntegrationTest
class AdminFoodPhotoTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var storage: FakeStorageObjectStore
    @Autowired private lateinit var foodRepository: FoodJpaRepository

    private val mapper = jacksonObjectMapper()

    init {
        fun sha256Of(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        fun keyOf(foodId: Long, sha256: String, ext: String) = "local/images/foods/$foodId/${sha256.take(16)}.$ext"
        fun longOf(sql: String): Long = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getLong(1) } } }
        fun stringOf(sql: String): String? = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { r -> if (r.next()) r.getString(1) else null } } }

        fun admin(request: MockHttpServletRequestDsl, body: Any, environment: String = "dev") {
            request.header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
            request.header("X-API-Version", "1.0")
            request.header("X-Target-Environment", environment)
            request.contentType = MediaType.APPLICATION_JSON
            request.content = mapper.writeValueAsString(body)
        }

        fun read(result: ResultActionsDsl, expected: Int): JsonNode =
            mapper.readTree(result.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8))

        fun presign(foodId: Long, contentType: String, length: Long, sha256: String, expected: Int = 200, environment: String = "dev") =
            read(mockMvc.post("/api/admin/foods/$foodId/photos/presign") { admin(this, mapOf("contentType" to contentType, "contentLength" to length, "contentSha256" to sha256), environment) }, expected)

        fun confirm(foodId: Long, key: String, setPrimary: Boolean? = null, expected: Int = 200) =
            read(mockMvc.post("/api/admin/foods/$foodId/photos/confirm") { admin(this, mapOf("key" to key, "setPrimary" to setPrimary)) }, expected)

        fun food(name: String = "청하", imageRef: String? = "images/webp/food/old_0001.webp") =
            foodRepository.save(Food(koreanName = name, description = "설명", imageRef = imageRef, contentStatus = FoodContentStatus.READY)).id

        beforeEach {
            TestTables.clearAll(dataSource)
            storage.heads.keys.filter { it.startsWith("local/images/foods/") }.forEach { storage.heads.remove(it) }
        }
        afterSpec { TestTables.clearAll(dataSource) }

        given("음식 실물 사진 업로드 URL 발급 — S-960") {
            `when`("JPEG 크기와 SHA-256 을 주면") {
                then("음식 id 접두 키 {prefix}/images/foods/{id}/{sha16}.jpg 로 PUT URL 을 준다") {
                    val id = food()
                    val sha256 = sha256Of("청하 앞면")

                    val payload = presign(id, "image/jpeg", 300_000, sha256).path("payload")

                    payload.path("key").asText() shouldBe keyOf(id, sha256, "jpg")
                    payload.path("requiredHeaders").path("Content-Type").asText() shouldBe "image/jpeg"
                }
            }
            `when`("형식·크기가 틀리거나 없는 음식이거나 환경이 다르면") {
                then("400 COMMON-002 · 400 UPLOAD-003 · 400 FOOD-001(기존 코드 상태 그대로) · 409 FOOD-026") {
                    val id = food()
                    presign(id, "image/gif", 1000, sha256Of("gif"), 400).path("code").asText() shouldBe "COMMON-002"
                    presign(id, "image/jpeg", 5L * 1024 * 1024 + 1, sha256Of("big"), 400).path("code").asText() shouldBe "UPLOAD-003"
                    presign(Long.MAX_VALUE, "image/jpeg", 1000, sha256Of("none"), 400).path("code").asText() shouldBe "FOOD-001"
                    presign(id, "image/jpeg", 1000, sha256Of("env"), 409, environment = "prod").path("code").asText() shouldBe "FOOD-026"
                }
            }
        }

        given("음식 실물 사진 저장 — confirm") {
            `when`("업로드가 확인되지 않거나 다른 음식의 키면") {
                then("400 FOOD-025 · 400 COMMON-002 이고 아무것도 저장하지 않는다") {
                    val id = food()
                    val other = food("보리차")
                    val sha256 = sha256Of("청하 앞면")
                    confirm(id, keyOf(id, sha256, "jpg"), expected = 400).path("code").asText() shouldBe "FOOD-025"
                    storage.stub(keyOf(id, sha256, "jpg"), "image/png", 1000, sha256Hex = sha256)
                    confirm(id, keyOf(id, sha256, "jpg"), expected = 400).path("code").asText() shouldBe "FOOD-025"
                    storage.stub(keyOf(other, sha256, "jpg"), "image/jpeg", 1000, sha256Hex = sha256)
                    confirm(id, keyOf(other, sha256, "jpg"), expected = 400).path("code").asText() shouldBe "COMMON-002"

                    longOf("SELECT COUNT(*) FROM food_image") shouldBe 0
                }
            }
            `when`("확인된 사진을 setPrimary 없이 저장하면") {
                then("UPLOADED 사진으로 갤러리에 붙고 대표(image_ref)는 그대로다 — 같은 키를 다시 확인해도 한 장") {
                    val id = food()
                    val sha256 = sha256Of("청하 앞면")
                    val key = keyOf(id, sha256, "jpg")
                    storage.stub(key, "image/jpeg", 300_000, sha256Hex = sha256)

                    val gallery = confirm(id, key).path("payload")
                    confirm(id, key)

                    gallery.path("items").single { it.path("imageKey").asText() == key }.let {
                        it.path("source").asText() shouldBe "UPLOADED"
                        it.path("isPrimary").asBoolean() shouldBe false
                    }
                    longOf("SELECT COUNT(*) FROM food_image WHERE food_id = $id AND image_key = '$key'") shouldBe 1
                    stringOf("SELECT image_ref FROM food WHERE id = $id") shouldBe "images/webp/food/old_0001.webp"
                }
            }
            `when`("setPrimary=true 로 저장하면") {
                then("그 사진이 유일한 대표가 되고 image_ref 가 바뀌며 벡터 UPSERT 가 쌓인다") {
                    val id = food()
                    val sha256 = sha256Of("청하 대표")
                    val key = keyOf(id, sha256, "webp")
                    storage.stub(key, "image/webp", 300_000, sha256Hex = sha256)

                    confirm(id, key, setPrimary = true)

                    stringOf("SELECT image_ref FROM food WHERE id = $id") shouldBe key
                    longOf("SELECT COUNT(*) FROM food_image WHERE food_id = $id AND is_primary = 1") shouldBe 1
                    stringOf("SELECT image_key FROM food_image WHERE food_id = $id AND is_primary = 1") shouldBe key
                    longOf("SELECT COUNT(*) FROM food_vector_outbox WHERE food_id = $id AND operation = 'UPSERT'") shouldBe 1
                }
            }
        }

        given("대표 사진이 이미 있는 음식에 setPrimary=true 로 저장하면 — Codex P2(#561)") {
            `when`("응답 갤러리를 보면") {
                then("대표는 정확히 하나(새 사진)이고 맨 앞이다") {
                    val id = food()
                    dataSource.connection.use { c -> c.createStatement().use { it.execute("INSERT INTO food_image (food_id, image_key, is_primary, source, sort_order, status, created_at, updated_at) VALUES ($id, 'images/webp/food/old_0001.webp', 1, 'GENERATED', 0, 'ACTIVE', NOW(6), NOW(6))") } }
                    val sha256 = sha256Of("청하 새 대표")
                    val key = keyOf(id, sha256, "jpg")
                    storage.stub(key, "image/jpeg", 300_000, sha256Hex = sha256)

                    val items = confirm(id, key, setPrimary = true).path("payload").path("items")

                    items.count { it.path("isPrimary").asBoolean() } shouldBe 1
                    items.first().path("imageKey").asText() shouldBe key
                }
            }
        }

        given("음식 시드 skipPipeline — KB-993") {
            `when`("skipPipeline=true 로 시드하면") {
                then("음식 행만 만들고 콘텐츠 요청을 쌓지 않는다(기본값은 지금처럼 쌓는다)") {
                    read(mockMvc.post("/api/admin/foods") { admin(this, mapOf("koreanNames" to listOf("수정과"), "skipPipeline" to true)) }, 200)
                    read(mockMvc.post("/api/admin/foods") { admin(this, mapOf("koreanNames" to listOf("식혜"))) }, 200)

                    val skipped = longOf("SELECT id FROM food WHERE korean_name = '수정과'")
                    val queued = longOf("SELECT id FROM food WHERE korean_name = '식혜'")
                    longOf("SELECT COUNT(*) FROM food_content_outbox WHERE food_id = $skipped") shouldBe 0
                    longOf("SELECT COUNT(*) FROM food_content_outbox WHERE food_id = $queued") shouldBe 1
                }
            }
        }
    }
}

package com.kbap.api.order

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import javax.sql.DataSource

@IntegrationTest
class OrderEditControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    init {
        val mapper = jacksonObjectMapper()

        fun seedMember(memberId: Long): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO member (id, provider, provider_uid, member_status,
                                        onboarding_completed, status, created_at, updated_at)
                    VALUES (?, 'GOOGLE', ?, 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE id = id
                    """,
                ).use { ps -> ps.setLong(1, memberId); ps.setString(2, "order-edit-$memberId"); ps.executeUpdate() }
            }

        fun accessToken(memberId: Long): String {
            seedMember(memberId)
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun seedUpload(memberId: Long, path: String) {
            seedMember(memberId)
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO uploaded_image (member_id, object_path, content_type, size_bytes,
                                                status, created_at, updated_at)
                    VALUES (?, ?, 'image/jpeg', 1024, 'ACTIVE', NOW(6), NOW(6))
                    """,
                ).use { ps -> ps.setLong(1, memberId); ps.setString(2, path); ps.executeUpdate() }
            }
        }

        fun seedReadyFood(koreanName: String, imageRef: String? = null): Long {
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO food (korean_name, description, spiciness, name_translations, description_translations,
                                      ingredients, image_ref, content_status, status, created_at, updated_at)
                    VALUES (?, '설명', 0, '{}', '{}', '[]', ?, 'READY', 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE content_status = 'READY', image_ref = VALUES(image_ref)
                    """,
                ).use { ps -> ps.setString(1, koreanName); ps.setString(2, imageRef); ps.executeUpdate() }
            }
            return dataSource.connection.use { c ->
                c.prepareStatement("SELECT id FROM food WHERE korean_name = ?").use { ps ->
                    ps.setString(1, koreanName)
                    ps.executeQuery().use { rs -> rs.next().shouldBeTrue(); rs.getLong(1) }
                }
            }
        }

        fun payloadOf(result: ResultActionsDsl): JsonNode =
            mapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8)).path("payload")

        fun placeOrder(memberId: Long, token: String, scanPath: String, vararg foodIds: Long): JsonNode {
            seedUpload(memberId, scanPath)
            val items = foodIds.mapIndexed { i, id -> """{"menuName":"메뉴$i","quantity":1,"price":1000,"foodId":$id}""" }
            val body = """{"imagePath":"$scanPath","items":[${items.joinToString(",")}]}"""
            val orderId = payloadOf(
                mockMvc.post("/api/orders") {
                    header("X-API-Version", "1.0")
                    header("Authorization", "Bearer $token")
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andExpect { status { isOk() } },
            ).path("orderId").asLong()
            return payloadOf(
                mockMvc.get("/api/orders/$orderId") {
                    header("X-API-Version", "1.0")
                    header("Authorization", "Bearer $token")
                },
            )
        }

        fun patchPlace(token: String?, orderId: Long, body: String): ResultActionsDsl =
            mockMvc.patch("/api/orders/$orderId/place") {
                header("X-API-Version", "1.0")
                token?.let { header("Authorization", "Bearer $it") }
                contentType = MediaType.APPLICATION_JSON
                content = body
            }

        fun putImage(token: String?, orderId: Long, itemId: Long, imagePath: String): ResultActionsDsl =
            mockMvc.put("/api/orders/$orderId/items/$itemId/image") {
                header("X-API-Version", "1.0")
                token?.let { header("Authorization", "Bearer $it") }
                contentType = MediaType.APPLICATION_JSON
                content = """{"imagePath":"$imagePath"}"""
            }

        fun deleteImage(token: String?, orderId: Long, itemId: Long): ResultActionsDsl =
            mockMvc.delete("/api/orders/$orderId/items/$itemId/image") {
                header("X-API-Version", "1.0")
                token?.let { header("Authorization", "Bearer $it") }
            }

        fun listThumbnails(token: String, orderId: Long): List<String> =
            payloadOf(
                mockMvc.get("/api/orders") {
                    header("X-API-Version", "1.0")
                    header("Authorization", "Bearer $token")
                },
            ).path("items").first { it.path("orderId").asLong() == orderId }.path("thumbnails").map { it.asText() }

        val placeBody = """{"placeId":"ChIJedit","name":"백년옥","address":"서울 중구 소공로 51","language":"en"}"""

        given("주문 장소 교체 — PATCH /api/orders/{orderId}/place") {
            `when`("본인 주문에 검색 결과 하나를 보내면") {
                then("스냅샷이 교체되고 갱신된 상세가 내려간다") {
                    val memberId = 9701L
                    val token = accessToken(memberId)
                    val food = seedReadyFood("편집장소음식")
                    val orderId = placeOrder(memberId, token, "order-edit/9701/menu.jpg", food).path("orderId").asLong()

                    val payload = payloadOf(patchPlace(token, orderId, placeBody).andExpect { status { isOk() } })

                    payload.path("orderId").asLong() shouldBe orderId
                    payload.path("place").path("placeId").asText() shouldBe "ChIJedit"
                    payload.path("place").path("name").asText() shouldBe "백년옥"
                    payload.path("place").path("address").asText() shouldBe "서울 중구 소공로 51"
                    payload.path("place").path("language").asText() shouldBe "en"
                    mockMvc.get("/api/orders/$orderId") {
                        header("X-API-Version", "1.0")
                        header("Authorization", "Bearer $token")
                    }.andExpect { jsonPath("$.payload.place.placeId") { value("ChIJedit") } }
                }
            }

            `when`("타인의 주문에 보내면") {
                then("404 ORDER-002 — 주문 존재 여부를 노출하지 않는다") {
                    val ownerId = 9702L
                    val orderId = placeOrder(ownerId, accessToken(ownerId), "order-edit/9702/menu.jpg", seedReadyFood("남의장소음식"))
                        .path("orderId").asLong()

                    patchPlace(accessToken(9703L), orderId, placeBody).andExpect {
                        status { isNotFound() }
                        jsonPath("$.code") { value("ORDER-002") }
                    }
                }
            }

            `when`("name 이 비어 있으면") {
                then("400 COMMON-002 로 거절하고 스냅샷은 그대로다") {
                    val memberId = 9704L
                    val token = accessToken(memberId)
                    val orderId = placeOrder(memberId, token, "order-edit/9704/menu.jpg", seedReadyFood("빈이름장소음식"))
                        .path("orderId").asLong()

                    patchPlace(token, orderId, """{"placeId":"ChIJx","name":" ","language":"en"}""").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }

            `when`("토큰 없이 보내면") {
                then("401 이다") {
                    patchPlace(null, 1L, placeBody).andExpect { status { isUnauthorized() } }
                }
            }
        }

        given("주문 항목 사진 교체 — PUT /api/orders/{orderId}/items/{itemId}/image") {
            `when`("본인이 ORDER_ITEM 용도로 올린 사진을 보내면") {
                then("userImageUrl 이 채워지고 imageRef·hasPhoto 는 카탈로그 기준 그대로이며, 목록 썸네일도 내 사진이 된다") {
                    val memberId = 9711L
                    val token = accessToken(memberId)
                    val food = seedReadyFood("사진교체음식", imageRef = "images/webp/food/catalog.webp")
                    val detail = placeOrder(memberId, token, "order-edit/9711/menu.jpg", food)
                    val orderId = detail.path("orderId").asLong()
                    val itemId = detail.path("items")[0].path("id").asLong()
                    val mine = "images/orders/2026/09/9711_mine.jpg"
                    seedUpload(memberId, mine)

                    val payload = payloadOf(putImage(token, orderId, itemId, mine).andExpect { status { isOk() } })

                    val item = payload.path("items")[0]
                    item.path("id").asLong() shouldBe itemId
                    item.path("userImageUrl").asText() shouldBe "https://cdn.test/$mine"
                    item.path("imageRef").asText() shouldBe "https://cdn.test/images/webp/food/catalog.webp"
                    item.path("hasPhoto").asBoolean() shouldBe true
                    listThumbnails(token, orderId) shouldBe listOf("https://cdn.test/$mine")
                }
            }

            `when`("타인이 올린 사진 경로를 보내면") {
                then("400 IMAGE-007 이고 항목은 그대로다") {
                    val memberId = 9712L
                    val token = accessToken(memberId)
                    val detail = placeOrder(memberId, token, "order-edit/9712/menu.jpg", seedReadyFood("남의사진음식"))
                    val orderId = detail.path("orderId").asLong()
                    val itemId = detail.path("items")[0].path("id").asLong()
                    val theirs = "images/orders/2026/09/9713_theirs.jpg"
                    seedUpload(9713L, theirs)

                    putImage(token, orderId, itemId, theirs).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("IMAGE-007") }
                    }
                    mockMvc.get("/api/orders/$orderId") {
                        header("X-API-Version", "1.0")
                        header("Authorization", "Bearer $token")
                    }.andExpect { jsonPath("$.payload.items[0].userImageUrl") { value(null) } }
                }
            }

            `when`("본인이 올렸지만 리뷰 용도(images/review/)인 사진을 보내면") {
                then("400 IMAGE-007 — 용도가 다르면 쓸 수 없다") {
                    val memberId = 9714L
                    val token = accessToken(memberId)
                    val detail = placeOrder(memberId, token, "order-edit/9714/menu.jpg", seedReadyFood("리뷰용도음식"))
                    val orderId = detail.path("orderId").asLong()
                    val itemId = detail.path("items")[0].path("id").asLong()
                    val reviewPhoto = "images/review/2026/09/9714_review.jpg"
                    seedUpload(memberId, reviewPhoto)

                    putImage(token, orderId, itemId, reviewPhoto).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("IMAGE-007") }
                    }
                }
            }

            `when`("다른 주문의 항목 id 를 보내면") {
                then("404 ORDER-004 — 항목이 그 주문에 없다") {
                    val memberId = 9715L
                    val token = accessToken(memberId)
                    val food = seedReadyFood("다른주문항목음식")
                    val first = placeOrder(memberId, token, "order-edit/9715/menu-a.jpg", food)
                    val second = placeOrder(memberId, token, "order-edit/9715/menu-b.jpg", food)
                    val mine = "images/orders/2026/09/9715_mine.jpg"
                    seedUpload(memberId, mine)

                    putImage(token, first.path("orderId").asLong(), second.path("items")[0].path("id").asLong(), mine).andExpect {
                        status { isNotFound() }
                        jsonPath("$.code") { value("ORDER-004") }
                    }
                }
            }

            `when`("타인의 주문에 보내면") {
                then("404 ORDER-002 다") {
                    val ownerId = 9716L
                    val detail = placeOrder(ownerId, accessToken(ownerId), "order-edit/9716/menu.jpg", seedReadyFood("남의주문사진음식"))
                    val intruder = 9717L
                    val token = accessToken(intruder)
                    val mine = "images/orders/2026/09/9717_mine.jpg"
                    seedUpload(intruder, mine)

                    putImage(token, detail.path("orderId").asLong(), detail.path("items")[0].path("id").asLong(), mine).andExpect {
                        status { isNotFound() }
                        jsonPath("$.code") { value("ORDER-002") }
                    }
                }
            }
        }

        given("주문 항목 사진 원복 — DELETE /api/orders/{orderId}/items/{itemId}/image") {
            `when`("내 사진으로 바꾼 항목을 원복하면") {
                then("userImageUrl 이 null 로 돌아가고 목록 썸네일도 카탈로그 사진이 된다") {
                    val memberId = 9721L
                    val token = accessToken(memberId)
                    val food = seedReadyFood("원복음식", imageRef = "images/webp/food/restore.webp")
                    val detail = placeOrder(memberId, token, "order-edit/9721/menu.jpg", food)
                    val orderId = detail.path("orderId").asLong()
                    val itemId = detail.path("items")[0].path("id").asLong()
                    val mine = "images/orders/2026/09/9721_mine.jpg"
                    seedUpload(memberId, mine)
                    putImage(token, orderId, itemId, mine).andExpect { status { isOk() } }

                    val payload = payloadOf(deleteImage(token, orderId, itemId).andExpect { status { isOk() } })

                    payload.path("items")[0].path("userImageUrl").isNull.shouldBeTrue()
                    payload.path("items")[0].path("imageRef").asText() shouldBe "https://cdn.test/images/webp/food/restore.webp"
                    listThumbnails(token, orderId) shouldBe listOf("https://cdn.test/images/webp/food/restore.webp")
                }
            }

            `when`("내 사진이 없던 항목을 원복하면") {
                then("200 이고 여전히 null 이다(멱등)") {
                    val memberId = 9722L
                    val token = accessToken(memberId)
                    val detail = placeOrder(memberId, token, "order-edit/9722/menu.jpg", seedReadyFood("멱등원복음식"))

                    deleteImage(token, detail.path("orderId").asLong(), detail.path("items")[0].path("id").asLong()).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items[0].userImageUrl") { value(null) }
                    }
                }
            }

            `when`("타인의 주문 항목을 원복하면") {
                then("404 ORDER-002 다") {
                    val ownerId = 9723L
                    val detail = placeOrder(ownerId, accessToken(ownerId), "order-edit/9723/menu.jpg", seedReadyFood("남의원복음식"))

                    deleteImage(accessToken(9724L), detail.path("orderId").asLong(), detail.path("items")[0].path("id").asLong()).andExpect {
                        status { isNotFound() }
                        jsonPath("$.code") { value("ORDER-002") }
                    }
                }
            }
        }
    }
}

package com.kbap.api.order

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class OrderControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var reverseGeocoder: FakeReverseGeocoder

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
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.setString(2, "order-test-$memberId")
                    ps.executeUpdate()
                }
            }

        fun accessToken(memberId: Long): String {
            seedMember(memberId)
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun seedVerifiedImage(memberId: Long, path: String) {
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

        fun seedReadyFood(koreanName: String): Long {
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO food (korean_name, description, spiciness, name_translations, description_translations,
                                      ingredients, content_status, status, created_at, updated_at)
                    VALUES (?, '설명', 0, '{}', '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE content_status = 'READY'
                    """,
                ).use { ps -> ps.setString(1, koreanName); ps.executeUpdate() }
            }
            return dataSource.connection.use { c ->
                c.prepareStatement("SELECT id FROM food WHERE korean_name = ?").use { ps ->
                    ps.setString(1, koreanName)
                    ps.executeQuery().use { rs -> rs.next().shouldBeTrue(); rs.getLong(1) }
                }
            }
        }

        fun setFoodImage(foodId: Long, imageRef: String): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement("UPDATE food SET image_ref = ? WHERE id = ?").use { ps ->
                    ps.setString(1, imageRef); ps.setLong(2, foodId); ps.executeUpdate()
                }
            }

        fun itemJson(menuName: String, quantity: Int, price: Int?, foodId: Long): String =
            """{"menuName":${mapper.writeValueAsString(menuName)},"quantity":$quantity,"price":${price ?: "null"},"foodId":$foodId}"""

        fun orderBody(
            imagePath: String,
            items: List<String>,
            latitude: String? = null,
            longitude: String? = null,
        ): String {
            val location = buildString {
                if (latitude != null) append(""","latitude":$latitude""")
                if (longitude != null) append(""","longitude":$longitude""")
            }
            return """{"imagePath":"$imagePath","items":[${items.joinToString(",")}]$location}"""
        }

        fun placeOrder(token: String?, body: String): ResultActionsDsl =
            mockMvc.post("/api/orders") {
                header("X-API-Version", "1.0")
                token?.let { header("Authorization", "Bearer $it") }
                contentType = MediaType.APPLICATION_JSON
                content = body
            }

        fun storedOrderOf(imagePath: String): List<String?> =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT member_id, latitude, longitude, road_address FROM orders WHERE image_path = ?",
                ).use { ps ->
                    ps.setString(1, imagePath)
                    ps.executeQuery().use { rs ->
                        rs.next().shouldBeTrue()
                        (1..4).map { rs.getString(it) }
                    }
                }
            }

        fun storedItemCountOf(imagePath: String): Long =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT COUNT(*) FROM order_item i JOIN orders o ON i.order_id = o.id WHERE o.image_path = ?",
                ).use { ps ->
                    ps.setString(1, imagePath)
                    ps.executeQuery().use { rs -> rs.next().shouldBeTrue(); rs.getLong(1) }
                }
            }

        fun listOrders(token: String, query: String = ""): ResultActionsDsl =
            mockMvc.get("/api/orders$query") {
                header("X-API-Version", "1.0")
                header("Authorization", "Bearer $token")
            }

        given("주문 저장 API — POST /api/orders") {
            `when`("메뉴 2종으로 주문하면") {
                then("주문 1건과 항목 2건이 저장된다") {
                    val memberId = 910L
                    val path = "order/910/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    val foodA = seedReadyFood("주문순두부")
                    val foodB = seedReadyFood("주문제육")

                    placeOrder(
                        accessToken(memberId),
                        orderBody(path, listOf(itemJson("순두부찌개", 2, 9000, foodA), itemJson("제육볶음", 1, 11000, foodB))),
                    ).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.orderId") { exists() }
                    }

                    storedOrderOf(path)[0] shouldBe "910"
                    storedItemCountOf(path) shouldBe 2L
                }
            }

            `when`("좌표를 함께 보내면") {
                then("좌표와 역지오코딩된 주소가 저장된다") {
                    val memberId = 911L
                    val path = "order/911/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    val food = seedReadyFood("주문좌표찌개")
                    reverseGeocoder.program("37.5636000", "126.9834000", "서울 중구 소공로 51")

                    placeOrder(
                        accessToken(memberId),
                        orderBody(path, listOf(itemJson("좌표찌개", 1, 8000, food)), latitude = "37.5636000", longitude = "126.9834000"),
                    ).andExpect { status { isOk() } }

                    val stored = storedOrderOf(path)
                    stored[1] shouldBe "37.5636000"
                    stored[2] shouldBe "126.9834000"
                    stored[3] shouldBe "서울 중구 소공로 51"
                }
            }

            `when`("좌표 없이 보내면") {
                then("위치 정보 없이 저장된다") {
                    val memberId = 912L
                    val path = "order/912/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    val food = seedReadyFood("주문무좌표찌개")

                    placeOrder(accessToken(memberId), orderBody(path, listOf(itemJson("무좌표찌개", 1, 7000, food))))
                        .andExpect { status { isOk() } }

                    val stored = storedOrderOf(path)
                    stored[1] shouldBe null
                    stored[2] shouldBe null
                    stored[3] shouldBe null
                }
            }

            `when`("역지오코딩이 실패하면") {
                then("좌표만 저장되고 주문은 성공한다") {
                    val memberId = 913L
                    val path = "order/913/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    val food = seedReadyFood("주문지오실패찌개")
                    reverseGeocoder.failAll = true
                    try {
                        placeOrder(
                            accessToken(memberId),
                            orderBody(path, listOf(itemJson("지오실패찌개", 1, 6000, food)), latitude = "37.1000000", longitude = "127.1000000"),
                        ).andExpect { status { isOk() } }
                    } finally {
                        reverseGeocoder.failAll = false
                    }

                    val stored = storedOrderOf(path)
                    stored[1] shouldBe "37.1000000"
                    stored[3] shouldBe null
                }
            }

            `when`("항목 없이 주문하면") {
                then("400 으로 거절된다") {
                    val memberId = 914L
                    val path = "order/914/menu.jpg"
                    seedVerifiedImage(memberId, path)

                    placeOrder(accessToken(memberId), orderBody(path, emptyList()))
                        .andExpect {
                            status { isBadRequest() }
                            jsonPath("$.code") { value("COMMON-002") }
                        }
                }
            }

            `when`("수량이 0 인 항목으로 주문하면") {
                then("400 으로 거절된다") {
                    val memberId = 915L
                    val path = "order/915/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    val food = seedReadyFood("주문수량영찌개")

                    placeOrder(accessToken(memberId), orderBody(path, listOf(itemJson("수량영찌개", 0, 5000, food))))
                        .andExpect { status { isBadRequest() } }
                }
            }

            `when`("좌표를 한쪽만 보내면") {
                then("400 으로 거절된다") {
                    val memberId = 916L
                    val path = "order/916/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    val food = seedReadyFood("주문반좌표찌개")

                    placeOrder(accessToken(memberId), orderBody(path, listOf(itemJson("반좌표찌개", 1, 5000, food)), latitude = "37.5"))
                        .andExpect { status { isBadRequest() } }
                }
            }

            `when`("이미 주문한 스캔으로 다시 주문하면") {
                then("409 ORDER-003 으로 거절된다") {
                    val memberId = 917L
                    val path = "order/917/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    val food = seedReadyFood("주문중복찌개")
                    val body = orderBody(path, listOf(itemJson("중복찌개", 1, 5000, food)))

                    placeOrder(accessToken(memberId), body).andExpect { status { isOk() } }
                    placeOrder(accessToken(memberId), body).andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("ORDER-003") }
                    }
                }
            }

            `when`("타인이 업로드한 이미지로 주문하면") {
                then("400 SCAN-001 로 거절된다") {
                    val ownerId = 918L
                    val intruderId = 919L
                    val path = "order/918/menu.jpg"
                    seedVerifiedImage(ownerId, path)
                    val food = seedReadyFood("주문남의사진찌개")

                    placeOrder(accessToken(intruderId), orderBody(path, listOf(itemJson("남의사진찌개", 1, 5000, food))))
                        .andExpect {
                            status { isBadRequest() }
                            jsonPath("$.code") { value("SCAN-001") }
                        }
                }
            }

            `when`("인증 없이 주문하면") {
                then("401 로 거절된다") {
                    placeOrder(null, orderBody("order/920/menu.jpg", listOf(itemJson("무인증찌개", 1, 5000, 1L))))
                        .andExpect { status { isUnauthorized() } }
                }
            }

            `when`("존재하지 않는 음식으로 주문하면") {
                then("400 ORDER-001 로 거절된다") {
                    val memberId = 921L
                    val path = "order/921/menu.jpg"
                    seedVerifiedImage(memberId, path)

                    placeOrder(accessToken(memberId), orderBody(path, listOf(itemJson("유령찌개", 1, 5000, 987654L))))
                        .andExpect {
                            status { isBadRequest() }
                            jsonPath("$.code") { value("ORDER-001") }
                        }
                }
            }

            `when`("foodId 가 0 이하이면") {
                then("400 으로 거절된다") {
                    val memberId = 922L
                    val path = "order/922/menu.jpg"
                    seedVerifiedImage(memberId, path)

                    placeOrder(accessToken(memberId), orderBody(path, listOf(itemJson("영푸드찌개", 1, 5000, 0L))))
                        .andExpect { status { isBadRequest() } }
                }
            }

            `when`("항목이 50개를 넘으면") {
                then("400 으로 거절된다") {
                    val memberId = 923L
                    val path = "order/923/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    val food = seedReadyFood("대량주문찌개")

                    placeOrder(accessToken(memberId), orderBody(path, (1..51).map { itemJson("대량주문찌개", 1, 1000, food) }))
                        .andExpect { status { isBadRequest() } }
                }
            }

            `when`("수량이 상한을 넘으면") {
                then("400 으로 거절된다") {
                    val memberId = 924L
                    val path = "order/924/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    val food = seedReadyFood("초과수량찌개")

                    placeOrder(accessToken(memberId), orderBody(path, listOf(itemJson("초과수량찌개", 1000, 1000, food))))
                        .andExpect { status { isBadRequest() } }
                }
            }
        }

        fun orderDetail(token: String, orderId: Long): ResultActionsDsl =
            mockMvc.get("/api/orders/$orderId") {
                header("X-API-Version", "1.0")
                header("Authorization", "Bearer $token")
            }

        fun orderIdOf(result: ResultActionsDsl): Long =
            mapper.readTree(result.andReturn().response.contentAsString).path("payload").path("orderId").asLong()

        given("주문 리스트 조회 API — GET /api/orders") {
            `when`("주문 3건을 저장한 회원이 조회하면") {
                then("최신순 목록이 내려간다") {
                    val memberId = 930L
                    val token = accessToken(memberId)
                    val food = seedReadyFood("리스트순두부")
                    (1..3).forEach { seq ->
                        val path = "order/930/menu-$seq.jpg"
                        seedVerifiedImage(memberId, path)
                        placeOrder(token, orderBody(path, listOf(itemJson("리스트순두부", seq, 1000 * seq, food))))
                            .andExpect { status { isOk() } }
                    }

                    val json = listOrders(token).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.totalCount") { doesNotExist() }
                        jsonPath("$.payload.items.length()") { value(3) }
                        jsonPath("$.payload.hasNext") { value(false) }
                    }.andReturn().response.contentAsString

                    val items = mapper.readTree(json).path("payload").path("items")
                    val ids = items.map { it.path("orderId").asLong() }
                    ids shouldBe ids.sortedDescending()
                    items[0].path("totalQuantity").asInt() shouldBe 3
                    (items[0].path("orderedAt").asLong() > 0) shouldBe true
                }
            }

            `when`("size 보다 주문이 많으면") {
                then("hasNext 와 nextCursor 로 다음 페이지를 준다") {
                    val memberId = 931L
                    val token = accessToken(memberId)
                    val food = seedReadyFood("커서순두부")
                    (1..3).forEach { seq ->
                        val path = "order/931/menu-$seq.jpg"
                        seedVerifiedImage(memberId, path)
                        placeOrder(token, orderBody(path, listOf(itemJson("커서순두부", 1, 1000, food))))
                            .andExpect { status { isOk() } }
                    }

                    val first = listOrders(token, "?size=2").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(2) }
                        jsonPath("$.payload.hasNext") { value(true) }
                    }.andReturn().response.contentAsString
                    val cursor = mapper.readTree(first).path("payload").path("nextCursor").asText()

                    listOrders(token, "?size=2&cursor=$cursor").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.hasNext") { value(false) }
                    }
                }
            }

            `when`("음식 5종을 담은 주문을 조회하면") {
                then("썸네일은 최대 4개까지만 내려간다") {
                    val memberId = 932L
                    val token = accessToken(memberId)
                    val path = "order/932/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    val items = (1..5).map { seq ->
                        val food = seedReadyFood("썸네일음식$seq")
                        setFoodImage(food, "images/webp/thumb-$seq.webp")
                        itemJson("썸네일음식$seq", 1, 1000, food)
                    }
                    placeOrder(token, orderBody(path, items)).andExpect { status { isOk() } }

                    listOrders(token).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items[0].thumbnails.length()") { value(4) }
                    }
                }
            }

            `when`("이미지가 없는 음식을 주문하면") {
                then("썸네일이 기본 대체 이미지 URL 로 내려간다") {
                    val memberId = 933L
                    val token = accessToken(memberId)
                    val path = "order/933/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    val food = seedReadyFood("이미지없는찌개")
                    placeOrder(token, orderBody(path, listOf(itemJson("이미지없는찌개", 1, 5000, food))))
                        .andExpect { status { isOk() } }

                    val json = listOrders(token).andReturn().response.contentAsString
                    val thumbnail = mapper.readTree(json).path("payload").path("items")[0]
                        .path("thumbnails")[0].asText()
                    thumbnail.contains("food_not_found") shouldBe true
                }
            }

            `when`("커서 형식이 올바르지 않으면") {
                then("400 FOOD-002 로 거절된다") {
                    listOrders(accessToken(936L), "?cursor=abc").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-002") }
                    }
                }
            }

            `when`("주문이 없는 회원이 조회하면") {
                then("빈 목록을 준다") {
                    listOrders(accessToken(934L)).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(0) }
                        jsonPath("$.payload.hasNext") { value(false) }
                    }
                }
            }

            `when`("좌표와 함께 저장한 주문을 조회하면") {
                then("도로명 주소는 내려가고 좌표는 노출되지 않는다") {
                    val memberId = 935L
                    val token = accessToken(memberId)
                    val path = "order/935/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    val food = seedReadyFood("주소노출찌개")
                    reverseGeocoder.program("37.5000000", "127.0000000", "서울 강남구 테헤란로 1")
                    placeOrder(
                        token,
                        orderBody(path, listOf(itemJson("주소노출찌개", 1, 5000, food)), latitude = "37.5000000", longitude = "127.0000000"),
                    ).andExpect { status { isOk() } }

                    val json = listOrders(token).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items[0].roadAddress") { value("서울 강남구 테헤란로 1") }
                    }.andReturn().response.contentAsString
                    json.contains("latitude") shouldBe false
                }
            }
        }

        given("주문 상세 조회 API — GET /api/orders/{orderId}") {
            `when`("항목별 수량·가격이 있는 주문을 조회하면") {
                then("항목 내역과 총가격이 내려간다") {
                    val memberId = 940L
                    val token = accessToken(memberId)
                    val path = "order/940/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    val foodA = seedReadyFood("상세순두부")
                    val foodB = seedReadyFood("상세제육")
                    val orderId = orderIdOf(
                        placeOrder(
                            token,
                            orderBody(path, listOf(itemJson("상세순두부", 2, 1000, foodA), itemJson("상세제육", 1, 3000, foodB))),
                        ).andExpect { status { isOk() } },
                    )

                    orderDetail(token, orderId).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.orderId") { value(orderId) }
                        jsonPath("$.payload.totalQuantity") { value(3) }
                        jsonPath("$.payload.totalPrice") { value(5000) }
                        jsonPath("$.payload.items.length()") { value(2) }
                        jsonPath("$.payload.items[0].menuName") { value("상세순두부") }
                        jsonPath("$.payload.items[0].quantity") { value(2) }
                        jsonPath("$.payload.items[0].price") { value(1000) }
                        jsonPath("$.payload.items[0].foodId") { value(foodA) }
                        jsonPath("$.payload.items[0].imageRef") { exists() }
                        jsonPath("$.payload.thumbnails") { doesNotExist() }
                    }
                }
            }

            `when`("가격 없는 항목이 섞인 주문을 조회하면") {
                then("총가격에서 제외된다") {
                    val memberId = 941L
                    val token = accessToken(memberId)
                    val path = "order/941/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    val foodA = seedReadyFood("상세가격있음")
                    val foodB = seedReadyFood("상세반찬")
                    val orderId = orderIdOf(
                        placeOrder(
                            token,
                            orderBody(path, listOf(itemJson("상세가격있음", 1, 9000, foodA), itemJson("상세반찬", 2, null, foodB))),
                        ).andExpect { status { isOk() } },
                    )

                    orderDetail(token, orderId).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.totalPrice") { value(9000) }
                        jsonPath("$.payload.items[1].price") { value(null) }
                    }
                }
            }

            `when`("사진이 없는 음식을 주문했다면") {
                then("상세의 imageRef 가 기본 대체 이미지로 내려간다") {
                    val memberId = 946L
                    val token = accessToken(memberId)
                    val path = "order/946/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    val food = seedReadyFood("상세이미지없음")
                    val orderId = orderIdOf(
                        placeOrder(token, orderBody(path, listOf(itemJson("상세이미지없음", 1, 5000, food))))
                            .andExpect { status { isOk() } },
                    )

                    val json = orderDetail(token, orderId).andReturn().response.contentAsString
                    val imageRef = mapper.readTree(json).path("payload").path("items")[0].path("imageRef").asText()
                    imageRef.contains("food_not_found") shouldBe true
                }
            }

            `when`("전 항목에 가격이 없으면") {
                then("총가격은 0 이다") {
                    val memberId = 942L
                    val token = accessToken(memberId)
                    val path = "order/942/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    val food = seedReadyFood("상세가격없음")
                    val orderId = orderIdOf(
                        placeOrder(token, orderBody(path, listOf(itemJson("상세가격없음", 2, null, food))))
                            .andExpect { status { isOk() } },
                    )

                    orderDetail(token, orderId).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.totalPrice") { value(0) }
                    }
                }
            }

            `when`("타인의 주문을 조회하면") {
                then("404 ORDER-002 로 거절된다") {
                    val ownerId = 943L
                    val ownerToken = accessToken(ownerId)
                    val path = "order/943/menu.jpg"
                    seedVerifiedImage(ownerId, path)
                    val food = seedReadyFood("상세남의주문")
                    val orderId = orderIdOf(
                        placeOrder(ownerToken, orderBody(path, listOf(itemJson("상세남의주문", 1, 5000, food))))
                            .andExpect { status { isOk() } },
                    )

                    orderDetail(accessToken(944L), orderId).andExpect {
                        status { isNotFound() }
                        jsonPath("$.code") { value("ORDER-002") }
                    }
                }
            }

            `when`("존재하지 않는 주문을 조회하면") {
                then("404 ORDER-002 로 거절된다") {
                    orderDetail(accessToken(945L), 999999L).andExpect {
                        status { isNotFound() }
                        jsonPath("$.code") { value("ORDER-002") }
                    }
                }
            }
        }
    }
}

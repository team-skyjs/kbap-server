package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.admin.AdminTestTokens.adminHeaders
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.admin.AdminAuditLogJpaRepository
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.order.OrderItemJpaRepository
import com.kbap.common.domain.order.OrderJpaRepository
import com.kbap.common.domain.order.model.Order
import com.kbap.common.domain.order.model.OrderItem
import com.kbap.common.domain.scan.ScanHistoryJpaRepository
import com.kbap.common.domain.scan.model.ScanHistory
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import java.time.LocalDate
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminOrderScanControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var memberRepository: MemberJpaRepository

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var orderRepository: OrderJpaRepository

    @Autowired
    private lateinit var orderItemRepository: OrderItemJpaRepository

    @Autowired
    private lateinit var scanHistoryRepository: ScanHistoryJpaRepository

    @Autowired
    private lateinit var auditLogRepository: AdminAuditLogJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    private val objectMapper = jacksonObjectMapper()

    init {
        fun member(uid: String): Member = memberRepository.save(Member(providerUid = uid, email = "$uid@test.com", nickname = uid, onboardingCompleted = true))
        fun food(name: String): Food = foodRepository.save(Food(koreanName = name, description = "설명", contentStatus = FoodContentStatus.READY, imageRef = "images/food/$name.webp"))
        fun order(member: Member, imagePath: String, vararg items: Pair<Food, Int>): Order {
            val order = orderRepository.save(Order.place(member.id, imagePath, null, null, "서울 중구"))
            items.forEach { (food, qty) -> orderItemRepository.save(OrderItem.place(order.id, food.id, food.displayName, qty, 9000)) }
            return order
        }

        fun token() = AdminTestTokens.adminAccessToken(tokenIssuer)
        fun get(path: String): MvcResult = mockMvc.get("/api/admin$path") { adminHeaders(token()) }.andReturn()
        fun json(r: MvcResult): Map<String, Any?> = objectMapper.readValue(r.response.contentAsString)

        @Suppress("UNCHECKED_CAST")
        fun payload(r: MvcResult) = json(r)["payload"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        fun items(r: MvcResult) = payload(r)["items"] as List<Map<String, Any?>>

        beforeContainer {
            AdminTestTables.clear(dataSource, "order_item", "orders", "scan_history", "admin_audit_log", "food", "member")
        }

        afterSpec {
            AdminTestTables.clear(dataSource, "order_item", "orders", "scan_history", "admin_audit_log", "food", "member")
        }

        given("GET /api/admin/orders · /{id} · DELETE /{id}") {
            `when`("회원별 주문을 조회하고 삭제하면") {
                then("목록 집계·상세 항목·소프트 삭제가 동작한다") {
                    val alice = member("alice")
                    val bob = member("bob")
                    val kimchi = food("김치찌개")
                    val bibim = food("비빔밥")
                    val o1 = order(alice, "orders/1.jpg", kimchi to 2, bibim to 1)
                    val o2 = order(bob, "orders/2.jpg", bibim to 3)
                    val today = LocalDate.now()

                    items(get("/orders")).map { it["id"] } shouldContainExactly listOf(o2.id.toInt(), o1.id.toInt())
                    items(get("/orders?memberId=${alice.id}")).map { it["id"] } shouldContainExactly listOf(o1.id.toInt())
                    items(get("/orders?from=$today&to=$today")).size shouldBe 2
                    items(get("/orders?to=${today.minusDays(1)}")).size shouldBe 0
                    val summary = items(get("/orders?memberId=${alice.id}")).single()
                    summary["itemCount"] shouldBe 2
                    summary["totalQuantity"] shouldBe 3
                    summary["totalPrice"] shouldBe 27000
                    summary["memberNickname"] shouldBe "alice"
                    (summary["scanImageUrl"] as String) shouldEndWith "orders/1.jpg"

                    val detail = payload(get("/orders/${o1.id}"))
                    @Suppress("UNCHECKED_CAST")
                    val detailItems = detail["items"] as List<Map<String, Any?>>
                    detailItems.map { it["foodDisplayName"] } shouldContainExactly listOf("김치찌개", "비빔밥")
                    detailItems.map { it["quantity"] } shouldContainExactly listOf(2, 1)
                    (detailItems.first()["foodImageUrl"] as String) shouldEndWith "김치찌개.webp"
                    detail["roadAddress"] shouldBe "서울 중구"

                    val deleted = mockMvc.delete("/api/admin/orders/${o1.id}") { adminHeaders(token()) }.andReturn()
                    deleted.response.status shouldBe 200
                    payload(deleted)["deletedItemCount"] shouldBe 2
                    orderRepository.findById(o1.id).isPresent shouldBe false
                    orderItemRepository.findByOrderIdOrderByIdAsc(o1.id).size shouldBe 0
                    auditLogRepository.findAll().single().action shouldBe AdminAuditAction.ORDER_DELETE

                    val missing = get("/orders/${o1.id}")
                    missing.response.status shouldBe 404
                    json(missing)["code"] shouldBe "ORDER-002"
                }
            }
        }

        given("GET /api/admin/scans") {
            `when`("미매칭·회원 필터로 조회하면") {
                then("조건에 맞는 스캔만 최신순으로 오고 음식명이 붙는다") {
                    val alice = member("alice")
                    val bob = member("bob")
                    val kimchi = food("김치찌개")
                    val matched = scanHistoryRepository.save(ScanHistory.record(alice.id, 9000, kimchi.id))
                    val unmatched = scanHistoryRepository.save(ScanHistory.record(alice.id, null, null))
                    val bobScan = scanHistoryRepository.save(ScanHistory.record(bob.id, 8000, kimchi.id))

                    items(get("/scans")).map { it["id"] } shouldContainExactly listOf(bobScan.id.toInt(), unmatched.id.toInt(), matched.id.toInt())
                    items(get("/scans?unmatched=true")).map { it["id"] } shouldContainExactly listOf(unmatched.id.toInt())
                    items(get("/scans?unmatched=false&memberId=${alice.id}")).map { it["id"] } shouldContainExactly listOf(matched.id.toInt())
                    items(get("/scans?to=${LocalDate.now().minusDays(1)}")).size shouldBe 0

                    val row = items(get("/scans?memberId=${bob.id}")).single()
                    row["memberNickname"] shouldBe "bob"
                    row["foodDisplayName"] shouldBe "김치찌개"
                    row["matched"] shouldBe true
                    row["price"] shouldBe 8000
                }
            }
        }
    }
}

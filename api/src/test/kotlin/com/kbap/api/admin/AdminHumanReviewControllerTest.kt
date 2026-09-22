package com.kbap.api.admin

import com.fasterxml.jackson.databind.JsonNode
import com.kbap.api.IntegrationTest
import com.kbap.common.domain.member.model.MemberRole
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put

@IntegrationTest
class AdminHumanReviewControllerTest : AdminFoodCatalogTestSupport() {
    init {
        val yejin = 7001L
        val jonghan = 7002L

        fun seedAdmin(id: Long, loginId: String, displayName: String?): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO admin_account (id, admin_id, admin_pwd, display_name, status, created_at, updated_at)
                    VALUES (?, ?, 'x', ?, 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE display_name = VALUES(display_name)
                    """,
                ).use { ps -> ps.setLong(1, id); ps.setString(2, loginId); ps.setString(3, displayName); ps.executeUpdate() }
            }

        fun adminToken(adminId: Long): String = tokenIssuer.issueAccessToken(adminId, MemberRole.ADMIN)

        fun seedAdmins() {
            seedAdmin(yejin, "yejin", "김예진")
            seedAdmin(jonghan, "jonghan", null)
        }

        fun mark(foodId: Long, adminId: Long = yejin, token: String = adminToken(adminId)): ResultActionsDsl =
            mockMvc.put("$path/$foodId/human-review") { header("Authorization", "Bearer $token") }

        fun clear(foodId: Long, adminId: Long = yejin): ResultActionsDsl =
            mockMvc.delete("$path/$foodId/human-review") { header("Authorization", "Bearer ${adminToken(adminId)}") }

        fun reviews(query: String = "", adminId: Long = yejin): ResultActionsDsl =
            mockMvc.get("/api/admin/human-reviews$query") { header("Authorization", "Bearer ${adminToken(adminId)}") }

        fun me(token: String): ResultActionsDsl =
            mockMvc.get("/api/admin/auth/me") { header("Authorization", "Bearer $token") }

        fun payloadOf(result: ResultActionsDsl): JsonNode =
            mapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8)).path("payload")

        fun setReviewed(foodId: Long, adminId: Long, secondsAgo: Int): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "UPDATE food SET human_reviewed_by = ?, human_reviewed_at = NOW(6) - INTERVAL ? SECOND WHERE id = ?",
                ).use { ps -> ps.setLong(1, adminId); ps.setInt(2, secondsAgo); ps.setLong(3, foodId); ps.executeUpdate() }
            }

        given("사람 검수 완료 기록 — PUT /api/admin/foods/{id}/human-review") {
            `when`("관리자가 음식에 검수 완료를 기록하면") {
                then("검수자 표시 이름·시각이 기록되고 목록·상세의 humanReview 에 그대로 보인다") {
                    seedAdmins()
                    val food = saveFood("검수기록음식")

                    val payload = payloadOf(mark(food.id).andExpect { status { isOk() } })

                    payload.path("foodId").asLong() shouldBe food.id
                    payload.path("humanReview").path("reviewedBy").path("id").asLong() shouldBe yejin
                    payload.path("humanReview").path("reviewedBy").path("displayName").asText() shouldBe "김예진"
                    payload.path("humanReview").path("reviewedAt").isTextual.shouldBeTrue()
                    getDetail(food.id).andExpect {
                        jsonPath("$.payload.humanReview.reviewedBy.displayName") { value("김예진") }
                        jsonPath("$.payload.contentStatus") { value("READY") }
                    }
                    getList().andExpect { jsonPath("$.payload.items[0].humanReview.reviewedBy.id") { value(yejin) } }
                }
            }

            `when`("다른 관리자가 같은 음식에 다시 기록하면") {
                then("최신 관리자·시각으로 덮어쓴다 — 이력은 남지 않는다") {
                    seedAdmins()
                    val food = saveFood("덮어쓰기음식")
                    val first = payloadOf(mark(food.id, yejin)).path("humanReview").path("reviewedAt").asText()

                    val payload = payloadOf(mark(food.id, jonghan).andExpect { status { isOk() } })

                    payload.path("humanReview").path("reviewedBy").path("id").asLong() shouldBe jonghan
                    payload.path("humanReview").path("reviewedBy").path("displayName").asText() shouldBe "jonghan"
                    (payload.path("humanReview").path("reviewedAt").asText() >= first).shouldBeTrue()
                    payloadOf(reviews()).path("summary").let { summary ->
                        summary.size() shouldBe 1
                        summary[0].path("adminId").asLong() shouldBe jonghan
                        summary[0].path("count").asLong() shouldBe 1
                    }
                }
            }

            `when`("토큰의 관리자 계정이 admin_account 에 없으면") {
                then("401 AUTH-003 이고 기록되지 않는다") {
                    seedAdmins()
                    val food = saveFood("계정없음음식")

                    mark(food.id, adminId = 7999L).andExpect {
                        status { isUnauthorized() }
                        jsonPath("$.code") { value("AUTH-003") }
                    }
                    getDetail(food.id).andExpect { jsonPath("$.payload.humanReview") { value(null) } }
                }
            }

            `when`("없는 음식에 기록하면") {
                then("400 FOOD-001 이다") {
                    seedAdmins()
                    mark(999_999L).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-001") }
                    }
                }
            }

            `when`("USER 역할 토큰으로 기록하면") {
                then("403 AUTH-008 이다") {
                    seedAdmins()
                    val food = saveFood("권한없음음식")
                    mark(food.id, token = tokenOf(MemberRole.USER)).andExpect {
                        status { isForbidden() }
                        jsonPath("$.code") { value("AUTH-008") }
                    }
                }
            }
        }

        given("사람 검수 기록 해제 — DELETE /api/admin/foods/{id}/human-review") {
            `when`("기록된 음식을 해제하면") {
                then("humanReview 가 null 로 돌아가고 목록·건수에서 빠진다") {
                    seedAdmins()
                    val food = saveFood("해제음식")
                    mark(food.id).andExpect { status { isOk() } }

                    val payload = payloadOf(clear(food.id).andExpect { status { isOk() } })

                    payload.path("foodId").asLong() shouldBe food.id
                    payload.path("humanReview").isNull.shouldBeTrue()
                    getDetail(food.id).andExpect { jsonPath("$.payload.humanReview") { value(null) } }
                    payloadOf(reviews()).let {
                        it.path("items").size() shouldBe 0
                        it.path("summary").size() shouldBe 0
                    }
                }
            }

            `when`("기록이 없던 음식을 해제하면") {
                then("200 이고 여전히 null 이다(멱등)") {
                    seedAdmins()
                    val food = saveFood("멱등해제음식")
                    clear(food.id).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.humanReview") { value(null) }
                    }
                }
            }
        }

        given("검수 기록 목록 — GET /api/admin/human-reviews") {
            `when`("두 관리자가 여러 음식을 검수했으면") {
                then("최신순 목록과 관리자별 건수(내림차순)가 내려가고, adminId 필터는 목록만 좁힌다") {
                    seedAdmins()
                    val a = saveFood("목록A")
                    val b = saveFood("목록B")
                    val c = saveFood("목록C")
                    saveFood("미검수D")
                    setReviewed(a.id, yejin, 30)
                    setReviewed(b.id, jonghan, 20)
                    setReviewed(c.id, yejin, 10)

                    val all = payloadOf(reviews().andExpect { status { isOk() } })
                    all.path("items").map { it.path("foodId").asLong() } shouldBe listOf(c.id, b.id, a.id)
                    all.path("items")[0].path("name").asText() shouldBe "목록C"
                    all.path("items")[0].path("deleted").asBoolean().shouldBeFalse()
                    all.path("items")[0].path("reviewer").path("displayName").asText() shouldBe "김예진"
                    all.path("items")[1].path("reviewer").path("displayName").asText() shouldBe "jonghan"
                    all.path("hasNext").asBoolean().shouldBeFalse()
                    all.path("summary").map { it.path("adminId").asLong() to it.path("count").asLong() } shouldBe
                        listOf(yejin to 2L, jonghan to 1L)
                    all.path("summary")[0].path("displayName").asText() shouldBe "김예진"

                    val filtered = payloadOf(reviews("?adminId=$jonghan"))
                    filtered.path("items").map { it.path("foodId").asLong() } shouldBe listOf(b.id)
                    filtered.path("summary").size() shouldBe 2
                }
            }

            `when`("기록이 페이지 크기(20)보다 많으면") {
                then("커서로 이어지고 마지막 페이지에서 끝난다") {
                    seedAdmins()
                    val ids = (1..21).map { i -> saveFood("페이징$i").id.also { setReviewed(it, yejin, 100 - i) } }

                    val first = payloadOf(reviews())
                    first.path("items").size() shouldBe 20
                    first.path("items")[0].path("foodId").asLong() shouldBe ids.last()
                    first.path("hasNext").asBoolean().shouldBeTrue()
                    val cursor = first.path("nextCursor").asLong()
                    cursor shouldBe ids[1]

                    val second = payloadOf(reviews("?cursor=$cursor"))
                    second.path("items").map { it.path("foodId").asLong() } shouldBe listOf(ids.first())
                    second.path("hasNext").asBoolean().shouldBeFalse()
                    second.path("nextCursor").isNull.shouldBeTrue()
                }
            }

            `when`("검수 기록이 없는 음식 id 를 커서로 주면") {
                then("400 FOOD-002 다") {
                    seedAdmins()
                    val food = saveFood("커서불량음식")
                    reviews("?cursor=${food.id}").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-002") }
                    }
                }
            }

            `when`("검수된 음식이 소프트삭제되면") {
                then("기록은 목록(deleted=true)·건수에 그대로 남는다 — 관리자가 한 일은 음식이 지워져도 사라지지 않는다") {
                    seedAdmins()
                    val kept = saveFood("살아있는검수음식")
                    val food = saveFood("삭제검수음식")
                    setReviewed(kept.id, yejin, 20)
                    mark(food.id).andExpect { status { isOk() } }
                    deleteFood(food.id).andExpect { status { isOk() } }

                    val payload = payloadOf(reviews())
                    payload.path("items").map { it.path("foodId").asLong() } shouldBe listOf(food.id, kept.id)
                    payload.path("items")[0].path("deleted").asBoolean().shouldBeTrue()
                    payload.path("items")[1].path("deleted").asBoolean().shouldBeFalse()
                    payload.path("summary")[0].path("count").asLong() shouldBe 2
                    getDeletedDetail(food.id).andExpect { jsonPath("$.payload.humanReview.reviewedBy.id") { value(yejin) } }
                }
            }
        }

        given("현재 관리자 — GET /api/admin/auth/me") {
            `when`("표시 이름이 있는 관리자가 조회하면") {
                then("표시 이름이 내려간다") {
                    seedAdmins()
                    me(adminToken(yejin)).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.id") { value(yejin) }
                        jsonPath("$.payload.loginId") { value("yejin") }
                        jsonPath("$.payload.displayName") { value("김예진") }
                    }
                }
            }

            `when`("표시 이름이 없는 관리자가 조회하면") {
                then("로그인 아이디가 표시 이름을 대신한다") {
                    seedAdmins()
                    me(adminToken(jonghan)).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.displayName") { value("jonghan") }
                    }
                }
            }

            `when`("토큰 없이 조회하면") {
                then("401 이다") {
                    mockMvc.get("/api/admin/auth/me").andExpect { status { isUnauthorized() } }
                }
            }
        }
    }
}

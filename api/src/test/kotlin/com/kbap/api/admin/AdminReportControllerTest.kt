package com.kbap.api.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.report.model.ReportHandleResult
import com.kbap.common.domain.report.model.ReportTargetType
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@IntegrationTest
class AdminReportControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var adminReportService: AdminReportService
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var entityManager: jakarta.persistence.EntityManager

    private val mapper = jacksonObjectMapper()

    init {
        val admin = 8563L
        val author = 85631L
        val reporter1 = 85632L
        val reporter2 = 85633L
        val review = 85630L

        fun exec(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

        fun scalar(sql: String): String? = dataSource.connection.use { c ->
            c.createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
        }

        fun seed() {
            TestTables.clearAll(dataSource)
            exec(
                "INSERT INTO admin_account (id, admin_id, admin_pwd, status, created_at, updated_at) " +
                    "VALUES ($admin, 'report-admin', 'x', 'ACTIVE', NOW(6), NOW(6)) ON DUPLICATE KEY UPDATE status = 'ACTIVE'",
            )
            listOf(author, reporter1, reporter2).forEach { id ->
                exec(
                    "INSERT INTO member (id, provider, provider_uid, member_status, onboarding_completed, status, review_count, " +
                        "unique_reviewed_food_count, created_at, updated_at) VALUES ($id, 'GOOGLE', 'report-$id', 'ACTIVE', 1, 'ACTIVE', " +
                        "${if (id == author) 1 else 0}, ${if (id == author) 1 else 0}, NOW(6), NOW(6))",
                )
            }
            exec(
                "INSERT INTO food (id, korean_name, description, spiciness, name_translations, description_translations, ingredients, " +
                    "content_status, status, created_at, updated_at) VALUES (85630, '신고음식', '설명', 0, '{}', '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))",
            )
            exec("INSERT INTO food_review (id, member_id, food_id, rating, content, status) VALUES ($review, $author, 85630, 1, '신고된 리뷰 본문', 'ACTIVE')")
        }

        fun report(memberId: Long?, installation: String, reason: String = "SPAM"): Long {
            exec(
                "INSERT INTO report (reporter_member_id, reporter_installation_id, target_type, target_id, reason) " +
                    "VALUES (${memberId ?: "NULL"}, '$installation', 'REVIEW', $review, '$reason')",
            )
            return scalar("SELECT MAX(id) FROM report")!!.toLong()
        }

        fun seedFourReports(): List<Long> = listOf(
            report(reporter1, "install-r1", "SPAM"),
            report(reporter2, "install-r2", "ABUSE"),
            report(null, "install-guest-0001", "SPAM"),
            report(reporter1, "install-r1", "OTHER"),
        )

        fun token() = tokenIssuer.issueAccessToken(admin, MemberRole.ADMIN)

        fun body(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.getContentAsString(Charsets.UTF_8))

        fun list(query: String = "") = body(
            mockMvc.get("/api/admin/reports$query") {
                header("X-API-Version", "1.0")
                header("Authorization", "Bearer ${token()}")
            }.andReturn().response,
        ).path("payload")

        fun handleTarget(result: String, note: String? = null) = mockMvc.patch("/api/admin/reports/targets/REVIEW/$review") {
            header("X-API-Version", "1.0")
            header("Authorization", "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(mapOf("result" to result, "note" to note))
        }.andReturn().response

        fun handleReport(id: Long, result: String) = mockMvc.patch("/api/admin/reports/$id") {
            header("X-API-Version", "1.0")
            header("Authorization", "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(mapOf("result" to result))
        }.andReturn().response

        fun statuses(): List<String> = dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT handle_status FROM report ORDER BY id").use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }

        given("신고 목록") {
            `when`("같은 리뷰에 회원 2 + 게스트 1 + 같은 회원 재신고 1 이 쌓이면") {
                then("대상 한 행으로 묶이고 totalCount 4·reporterCount 3·pendingCount 4, 펼침 항목 4건이 최근순이다") {
                    seed()
                    val ids = seedFourReports()

                    val page = list()

                    page.path("totalCount").asLong() shouldBe 1
                    val group = page.path("items")[0]
                    group.path("target").path("type").asText() shouldBe "REVIEW"
                    group.path("target").path("id").asLong() shouldBe review
                    group.path("target").path("authorMemberId").asLong() shouldBe author
                    group.path("target").path("contentPreview").asText() shouldBe "신고된 리뷰 본문"
                    group.path("target").path("exists").asBoolean() shouldBe true
                    group.path("totalCount").asInt() shouldBe 4
                    group.path("reporterCount").asInt() shouldBe 3
                    group.path("pendingCount").asInt() shouldBe 4
                    group.path("latestReason").asText() shouldBe "OTHER"
                    group.path("items").map { it.path("id").asLong() } shouldBe ids.reversed()
                    group.path("items").map { it.path("reporterLabel").asText() }.toSet() shouldBe
                        setOf("member:$reporter1", "member:$reporter2", "게스트(install-)")
                }
            }
        }

        given("아주 큰 페이지 번호") {
            `when`("page 가 Int 최댓값이면") {
                then("오프셋이 넘쳐 DB 오류가 나지 않고 빈 페이지 200 이다") {
                    seed()
                    seedFourReports()

                    val response = mockMvc.get("/api/admin/reports?page=2147483647") {
                        header("X-API-Version", "1.0")
                        header("Authorization", "Bearer ${token()}")
                    }.andReturn().response

                    response.status shouldBe 200
                    body(response).path("payload").path("items").size() shouldBe 0
                }
            }
        }

        given("대상 단위 처리") {
            `when`("DISMISSED 로 처리하면") {
                then("같은 대상 PENDING 4건이 전부 HANDLED·처리자·시각·메모가 남고, 리뷰는 그대로 노출된다. 목록은 HANDLED 쪽으로 옮겨간다") {
                    seed()
                    seedFourReports()

                    val response = handleTarget("DISMISSED", "광고 아님")

                    response.status shouldBe 200
                    body(response).path("payload").path("handledCount").asInt() shouldBe 4
                    body(response).path("payload").path("contentDeleted").asBoolean() shouldBe false
                    statuses() shouldBe List(4) { "HANDLED" }
                    scalar("SELECT COUNT(*) FROM report WHERE handle_result = 'DISMISSED' AND handled_by = $admin AND handled_at IS NOT NULL AND handle_note = '광고 아님'") shouldBe "4"
                    scalar("SELECT status FROM food_review WHERE id = $review") shouldBe "ACTIVE"
                    list().path("totalCount").asLong() shouldBe 0
                    list("?handleStatus=HANDLED").path("items")[0].path("pendingCount").asInt() shouldBe 0
                }
            }

            `when`("이미 처리된 대상을 다시 처리하면") {
                then("409 REPORT-006") {
                    seed()
                    seedFourReports()
                    handleTarget("DISMISSED").status shouldBe 200

                    val again = handleTarget("CONTENT_DELETED")

                    again.status shouldBe 409
                    body(again).path("code").asText() shouldBe "REPORT-006"
                    scalar("SELECT status FROM food_review WHERE id = $review") shouldBe "ACTIVE"
                }
            }
        }

        given("신고 id 단위 처리") {
            `when`("CONTENT_DELETED 로 처리하면") {
                then("리뷰가 소프트 삭제되고(작성자 리뷰 수·랭킹 이벤트도 작성자 삭제와 같게) 같은 대상 PENDING 전부 HANDLED, 같은 신고 재처리는 409") {
                    seed()
                    val ids = seedFourReports()

                    val response = handleReport(ids[1], "CONTENT_DELETED")

                    response.status shouldBe 200
                    body(response).path("payload").path("contentDeleted").asBoolean() shouldBe true
                    body(response).path("payload").path("handledCount").asInt() shouldBe 4
                    scalar("SELECT status FROM food_review WHERE id = $review") shouldBe "DELETED"
                    scalar("SELECT review_count FROM member WHERE id = $author") shouldBe "0"
                    scalar("SELECT COUNT(*) FROM member_ranking_event WHERE review_id = $review AND event = 'REVIEW_DELETED'") shouldBe "1"
                    statuses() shouldBe List(4) { "HANDLED" }
                    list("?handleStatus=HANDLED").path("items")[0].path("target").path("exists").asBoolean() shouldBe false

                    val again = handleReport(ids[0], "CONTENT_DELETED")
                    again.status shouldBe 409
                    body(again).path("code").asText() shouldBe "REPORT-006"
                }
            }

            `when`("이미 삭제된 리뷰의 남은 신고를 CONTENT_DELETED 로 처리하면") {
                then("멱등하게 200 — 리뷰는 다시 지우지 않고(contentDeleted=false) 남은 PENDING 만 처리한다") {
                    seed()
                    val id = report(reporter1, "install-r1")
                    exec("UPDATE food_review SET status = 'DELETED' WHERE id = $review")

                    val response = handleReport(id, "CONTENT_DELETED")

                    response.status shouldBe 200
                    body(response).path("payload").path("contentDeleted").asBoolean() shouldBe false
                    statuses() shouldBe listOf("HANDLED")
                    scalar("SELECT review_count FROM member WHERE id = $author") shouldBe "1"
                }
            }

            `when`("작성자가 정지 회원인 리뷰를 CONTENT_DELETED 로 처리하면") {
                then("탈퇴가 아니므로 작성자 삭제와 같게 리뷰 수를 줄이고 랭킹 이벤트를 남긴다") {
                    seed()
                    exec("UPDATE member SET member_status = 'SUSPENDED' WHERE id = $author")
                    val id = report(reporter1, "install-r1")

                    val response = handleReport(id, "CONTENT_DELETED")

                    response.status shouldBe 200
                    scalar("SELECT status FROM food_review WHERE id = $review") shouldBe "DELETED"
                    scalar("SELECT review_count FROM member WHERE id = $author") shouldBe "0"
                    scalar("SELECT unique_reviewed_food_count FROM member WHERE id = $author") shouldBe "0"
                    scalar("SELECT COUNT(*) FROM member_ranking_event WHERE review_id = $review AND event = 'REVIEW_DELETED'") shouldBe "1"
                }
            }

            `when`("없는 신고 id 를 처리하면") {
                then("404 REPORT-007") {
                    seed()

                    val response = handleReport(999_999L, "DISMISSED")

                    response.status shouldBe 404
                    body(response).path("code").asText() shouldBe "REPORT-007"
                }
            }
        }

        given("같은 대상의 동시 처리") {
            `when`("한 관리자의 처리가 대상 신고를 잠근 채 커밋을 미루는 동안 다른 관리자가 같은 대상을 처리하면") {
                then("뒤 요청은 앞 커밋을 기다린 뒤 409 REPORT-006 이다 — 하나만 성공한다") {
                    seed()
                    seedFourReports()
                    val held = CountDownLatch(1)
                    val executor = Executors.newSingleThreadExecutor()
                    val first = executor.submit {
                        TransactionTemplate(transactionManager).executeWithoutResult {
                            adminReportService.handleTarget(ReportTargetType.REVIEW, review, ReportHandleResult.CONTENT_DELETED, null, admin)
                            held.countDown()
                            Thread.sleep(1_500)
                        }
                    }
                    executor.shutdown()
                    held.await(30, TimeUnit.SECONDS) shouldBe true

                    val second = handleTarget("DISMISSED")
                    first.get(30, TimeUnit.SECONDS)
                    second.status shouldBe 409
                    body(second).path("code").asText() shouldBe "REPORT-006"
                    scalar("SELECT COUNT(*) FROM report WHERE handle_result = 'CONTENT_DELETED'") shouldBe "4"
                    scalar("SELECT status FROM food_review WHERE id = $review") shouldBe "DELETED"
                }
            }

            `when`("신고 R 을 처리하려는 사이 다른 처리가 R 을 처리하고 같은 대상에 새 신고 S 가 들어와 커밋되면") {
                then("R 처리 요청은 409 REPORT-006 — 나중에 들어온 S 를 대신 처리하지 않는다") {
                    seed()
                    val r = report(reporter1, "install-r1")
                    val held = CountDownLatch(1)
                    val executor = Executors.newSingleThreadExecutor()
                    val first = executor.submit {
                        TransactionTemplate(transactionManager).executeWithoutResult {
                            adminReportService.handleTarget(ReportTargetType.REVIEW, review, ReportHandleResult.DISMISSED, null, admin)
                            entityManager.createNativeQuery(
                                "INSERT INTO report (reporter_member_id, reporter_installation_id, target_type, target_id, reason) " +
                                    "VALUES ($reporter2, 'install-r2-late', 'REVIEW', $review, 'SPAM')",
                            ).executeUpdate()
                            held.countDown()
                            Thread.sleep(1_500)
                        }
                    }
                    executor.shutdown()
                    held.await(30, TimeUnit.SECONDS) shouldBe true

                    val second = handleReport(r, "DISMISSED")
                    first.get(30, TimeUnit.SECONDS)

                    second.status shouldBe 409
                    body(second).path("code").asText() shouldBe "REPORT-006"
                    scalar("SELECT COUNT(*) FROM report WHERE handle_status = 'PENDING'") shouldBe "1"
                }
            }

            `when`("모더레이션 삭제가 리뷰를 잠근 채 커밋을 미루는 동안 작성자가 같은 리뷰를 지우면") {
                then("작성자 요청은 앞 커밋을 기다린 뒤 리뷰 없음(REVIEW-001) — 교착 희생 409 가 아니고, 리뷰 수·랭킹 이벤트가 두 번 반영되지 않는다") {
                    seed()
                    exec("INSERT INTO food_review (member_id, food_id, rating, content, status) VALUES ($author, 85630, 5, '작성자의 다른 리뷰', 'ACTIVE')")
                    exec("UPDATE member SET review_count = 2 WHERE id = $author")
                    report(reporter1, "install-r1")
                    val held = CountDownLatch(1)
                    val executor = Executors.newSingleThreadExecutor()
                    val moderation = executor.submit {
                        TransactionTemplate(transactionManager).executeWithoutResult {
                            adminReportService.handleTarget(ReportTargetType.REVIEW, review, ReportHandleResult.CONTENT_DELETED, null, admin)
                            held.countDown()
                            Thread.sleep(1_500)
                        }
                    }
                    executor.shutdown()
                    held.await(30, TimeUnit.SECONDS) shouldBe true

                    val byAuthor = mockMvc.delete("/api/reviews/$review") {
                        header("X-API-Version", "1.0")
                        header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(author, MemberRole.USER)}")
                    }.andReturn().response
                    moderation.get(30, TimeUnit.SECONDS)

                    byAuthor.status shouldBe 400
                    body(byAuthor).path("code").asText() shouldBe "REVIEW-001"
                    scalar("SELECT review_count FROM member WHERE id = $author") shouldBe "1"
                    scalar("SELECT COUNT(*) FROM member_ranking_event WHERE review_id = $review AND event = 'REVIEW_DELETED'") shouldBe "1"
                }
            }

            `when`("처리 트랜잭션이 커밋을 미루는 동안 같은 대상에 새 신고가 들어오면") {
                then("새 신고는 처리 뒤에 들어가 PENDING 으로 남는다 — 다음 처리 대상") {
                    seed()
                    seedFourReports()
                    val held = CountDownLatch(1)
                    val executor = Executors.newFixedThreadPool(2)
                    val handling = executor.submit {
                        TransactionTemplate(transactionManager).executeWithoutResult {
                            adminReportService.handleTarget(ReportTargetType.REVIEW, review, ReportHandleResult.DISMISSED, null, admin)
                            held.countDown()
                            Thread.sleep(1_500)
                        }
                    }
                    held.await(30, TimeUnit.SECONDS) shouldBe true
                    val inserting = executor.submit<Long> { report(reporter2, "install-r2-late") }
                    executor.shutdown()
                    handling.get(30, TimeUnit.SECONDS)
                    val lateId = inserting.get(30, TimeUnit.SECONDS)

                    scalar("SELECT handle_status FROM report WHERE id = $lateId") shouldBe "PENDING"
                    scalar("SELECT COUNT(*) FROM report WHERE handle_status = 'HANDLED'") shouldBe "4"
                    list().path("items")[0].path("pendingCount").asInt() shouldBe 1
                }
            }
        }

        given("처리 대상 잠금 질의") {
            `when`("실행 계획을 보면") {
                then("(target_type, target_id) 인덱스로 그 대상 행만 잠근다 — 처리가 바꾸는 handle_status 인덱스로 잠그면 동시 처리끼리 교착한다") {
                    seed()
                    seedFourReports()
                    val plan = dataSource.connection.use { c ->
                        c.createStatement().use { s ->
                            s.executeQuery(
                                "EXPLAIN SELECT * FROM report FORCE INDEX (idx_report_target) WHERE target_type = 'REVIEW' AND target_id = $review " +
                                    "AND handle_status = 'PENDING' AND status = 'ACTIVE' FOR UPDATE",
                            ).use { rs -> rs.next(); rs.getString("key") }
                        }
                    }

                    plan shouldBe "idx_report_target"
                }
            }
        }
    }
}

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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@IntegrationTest
class AdminProcessedFoodCutoutReviewTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var storage: FakeStorageObjectStore

    private val mapper = jacksonObjectMapper()

    init {
        val modifiedAt = Instant.parse("2026-10-30T05:00:00Z")
        var adminId = 0L

        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

        fun queryLong(sql: String): Long = dataSource.connection.use { c -> c.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } } }

        fun row(id: Long): Map<String, String?> = dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    "SELECT cutout_image_url, cutout_review_status, cutout_route, cutout_coverage, cutout_reviewed_by, cutout_review_reason, cutout_reviewed_at FROM processed_food_product WHERE id = $id",
                ).use { rs ->
                    rs.next()
                    listOf("url", "status", "route", "coverage", "reviewedBy", "reason", "reviewedAt").mapIndexed { i, name -> name to rs.getString(i + 1) }.toMap()
                }
            }
        }

        fun keyOf(reportNo: String) = "local/processed/cutout/$reportNo.png"
        fun urlOf(reportNo: String, at: Instant = modifiedAt) = "https://cdn.test/${keyOf(reportNo)}?v=${at.epochSecond}"
        fun haccpOf(reportNo: String) = "http://www.haccp.or.kr/fresh/prdimg/$reportNo.jpg"

        fun seedProduct(reportNo: String, name: String = "검수칩$reportNo"): Long {
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, image_url, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES ('$reportNo', '$name', '$name', '과자', '${haccpOf(reportNo)}', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
            )
            return queryLong("SELECT id FROM processed_food_product WHERE report_no = '$reportNo'")
        }

        fun uploaded(reportNo: String, at: Instant = modifiedAt) = storage.stub(keyOf(reportNo), "image/png", 50_000, at)

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

        fun item(productId: Long, reportNo: String, route: String = "vision", coverage: Double? = 0.42) =
            mapOf("productId" to productId, "key" to keyOf(reportNo), "route" to route, "coverage" to coverage)

        fun bulkRegister(items: List<Any>, expected: Int = 200) =
            read(mockMvc.post("/api/admin/processed-foods/cutout-images/bulk-register") { admin(this, items) }, expected)

        fun review(id: Long, approved: Boolean, reason: String? = null, expected: Int = 200, expectedCutoutImageUrl: String? = null) =
            read(
                mockMvc.post("/api/admin/processed-foods/$id/cutout-image/review") {
                    admin(this, mapOf("approved" to approved, "reason" to reason, "expectedCutoutImageUrl" to expectedCutoutImageUrl))
                },
                expected,
            )

        fun bulkApprove(minCoverage: Double, dryRun: Boolean) =
            read(mockMvc.post("/api/admin/processed-foods/cutout-images/bulk-approve") { admin(this, mapOf("minCoverage" to minCoverage, "dryRun" to dryRun)) }, 200).path("payload")

        fun list(query: String) =
            read(mockMvc.get("/api/admin/processed-foods/cutout-images?$query") { admin(this) }, 200).path("payload")

        fun appImage(id: Long): String =
            read(mockMvc.get("/api/processed-foods/$id") { header("X-API-Version", "3.2") }, 200).path("payload").path("image").path("url").asText()

        fun httpsHaccp(reportNo: String) = haccpOf(reportNo).replace("http://", "https://")

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            storage.heads.keys.removeIf { it.startsWith("local/processed/cutout/") }
            storage.headDelayMillis = 0
            execute("INSERT IGNORE INTO admin_account (admin_id, admin_pwd, status, created_at, updated_at) VALUES ('cutout-reviewer', 'x', 'ACTIVE', NOW(6), NOW(6))")
            adminId = queryLong("SELECT id FROM admin_account WHERE admin_id = 'cutout-reviewer'")
        }
        afterSpec {
            storage.headDelayMillis = 0
            execute("DELETE FROM processed_food_product")
        }

        given("누끼 일괄 등록") {
            `when`("S3 에 PNG 가 있는 항목을 등록하면") {
                then("PENDING 으로 저장되고 앱 imageUrl 은 여전히 HACCP 원본이다") {
                    val id = seedProduct("BR-1")
                    uploaded("BR-1")

                    bulkRegister(listOf(item(id, "BR-1", coverage = 0.8123))).path("payload").let {
                        it.path("registered").asInt() shouldBe 1
                        it.path("failures").size() shouldBe 0
                    }

                    row(id).let {
                        it["url"] shouldBe urlOf("BR-1")
                        it["status"] shouldBe "PENDING"
                        it["route"] shouldBe "vision"
                        it["coverage"] shouldBe "0.8123"
                    }
                    appImage(id) shouldBe httpsHaccp("BR-1")
                }
            }

            `when`("일부 항목이 잘못되면") {
                then("잘못된 항목만 productId·사유로 돌려주고 나머지는 저장한다") {
                    val ok = seedProduct("BR-OK")
                    val notUploaded = seedProduct("BR-NONE")
                    val wrongKey = seedProduct("BR-KEY")
                    val badRoute = seedProduct("BR-ROUTE")
                    val badCoverage = seedProduct("BR-COV")
                    val duplicated = seedProduct("BR-DUP")
                    listOf("BR-OK", "BR-KEY", "BR-ROUTE", "BR-COV", "BR-DUP").forEach { uploaded(it) }
                    storage.stub(keyOf("BR-JPG"), "image/jpeg", 1000, modifiedAt)
                    val jpeg = seedProduct("BR-JPG")

                    val payload = bulkRegister(
                        listOf(
                            item(ok, "BR-OK"),
                            item(notUploaded, "BR-NONE"),
                            item(wrongKey, "BR-OK"),
                            item(badRoute, "BR-ROUTE", route = "magic"),
                            item(badCoverage, "BR-COV", coverage = 1.5),
                            item(duplicated, "BR-DUP"),
                            item(duplicated, "BR-DUP"),
                            item(jpeg, "BR-JPG"),
                            item(Long.MAX_VALUE, "BR-OK"),
                        ),
                    ).path("payload")

                    payload.path("registered").asInt() shouldBe 1
                    payload.path("failures").map { it.path("productId").asLong() to it.path("reason").asText() } shouldContainExactlyInAnyOrder listOf(
                        notUploaded to "NOT_UPLOADED",
                        wrongKey to "KEY_MISMATCH",
                        badRoute to "INVALID_ROUTE",
                        badCoverage to "INVALID_COVERAGE",
                        duplicated to "DUPLICATE",
                        duplicated to "DUPLICATE",
                        jpeg to "NOT_UPLOADED",
                        Long.MAX_VALUE to "PRODUCT_NOT_FOUND",
                    )
                    row(ok)["status"] shouldBe "PENDING"
                    listOf(notUploaded, wrongKey, badRoute, badCoverage, duplicated, jpeg).forEach { row(it)["status"] shouldBe null }
                }
            }

            `when`("500건을 넘거나 비어 있으면") {
                then("400 COMMON-002 다") {
                    val id = seedProduct("BR-MAX")
                    bulkRegister(List(501) { item(id, "BR-MAX") }, 400).path("code").asText() shouldBe "COMMON-002"
                    bulkRegister(emptyList(), 400).path("code").asText() shouldBe "COMMON-002"
                }
            }

            `when`("같은 오브젝트를 다시 등록하면") {
                then("검수 상태를 유지하고, 다시 올린 오브젝트(수정 시각이 다름)면 PENDING 으로 돌린다") {
                    val id = seedProduct("BR-AGAIN")
                    uploaded("BR-AGAIN")
                    bulkRegister(listOf(item(id, "BR-AGAIN")))
                    review(id, approved = true)

                    bulkRegister(listOf(item(id, "BR-AGAIN"))).path("payload").path("registered").asInt() shouldBe 1
                    row(id)["status"] shouldBe "APPROVED"
                    appImage(id) shouldBe urlOf("BR-AGAIN")

                    val reuploadedAt = modifiedAt.plusSeconds(60)
                    uploaded("BR-AGAIN", reuploadedAt)
                    bulkRegister(listOf(item(id, "BR-AGAIN")))
                    row(id).let {
                        it["status"] shouldBe "PENDING"
                        it["url"] shouldBe urlOf("BR-AGAIN", reuploadedAt)
                        it["reviewedBy"] shouldBe null
                    }
                    appImage(id) shouldBe httpsHaccp("BR-AGAIN")
                }
            }

            `when`("교체 등록이 행을 읽은 사이 조건 일괄 승인이 그 행을 승인하면 — Codex P2(#495)") {
                then("교체된 누끼가 검수 없이 APPROVED 로 남지 않고 PENDING 이다") {
                    val id = seedProduct("BR-RACE")
                    uploaded("BR-RACE")
                    bulkRegister(listOf(item(id, "BR-RACE", coverage = 0.9)))
                    val reuploadedAt = modifiedAt.plusSeconds(60)
                    uploaded("BR-RACE", reuploadedAt)

                    dataSource.connection.use { holder ->
                        holder.autoCommit = false
                        holder.createStatement().use { it.executeQuery("SELECT id FROM processed_food_product WHERE id = $id FOR UPDATE").close() }
                        val pool = Executors.newSingleThreadExecutor()
                        val registration = pool.submit<JsonNode> { bulkRegister(listOf(item(id, "BR-RACE", coverage = 0.9))) }
                        Thread.sleep(1_500)
                        registration.isDone shouldBe false
                        holder.createStatement().use {
                            it.executeUpdate("UPDATE processed_food_product SET cutout_review_status = 'APPROVED', cutout_reviewed_by = $adminId, cutout_reviewed_at = NOW(6) WHERE id = $id")
                        }
                        holder.commit()
                        registration.get(10, TimeUnit.SECONDS).path("payload").path("registered").asInt() shouldBe 1
                        pool.shutdown()
                    }

                    row(id).let {
                        it["url"] shouldBe urlOf("BR-RACE", reuploadedAt)
                        it["status"] shouldBe "PENDING"
                        it["reviewedBy"] shouldBe null
                    }
                    appImage(id) shouldBe httpsHaccp("BR-RACE")
                }
            }

            `when`("S3 확인이 느리면") {
                then("최대 8개씩 병렬로 확인한다") {
                    storage.headDelayMillis = 100
                    val items = (1..16).map { n ->
                        val reportNo = "BR-PAR-$n"
                        uploaded(reportNo)
                        item(seedProduct(reportNo), reportNo)
                    }

                    val startedAt = System.nanoTime()
                    bulkRegister(items).path("payload").path("registered").asInt() shouldBe 16
                    val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

                    elapsedMillis shouldBeLessThan 1_000L
                }
            }
        }

        given("누끼 한 장 검수") {
            `when`("승인하면") {
                then("앱 imageUrl 이 누끼가 되고 검수자·시각이 남는다") {
                    val id = seedProduct("RV-OK")
                    uploaded("RV-OK")
                    bulkRegister(listOf(item(id, "RV-OK")))

                    review(id, approved = true).path("payload").let {
                        it.path("status").asText() shouldBe "APPROVED"
                        it.path("imageUrl").asText() shouldBe urlOf("RV-OK")
                    }
                    row(id).let {
                        it["status"] shouldBe "APPROVED"
                        it["reviewedBy"] shouldBe adminId.toString()
                        (it["reviewedAt"] != null) shouldBe true
                    }
                    appImage(id) shouldBe urlOf("RV-OK")
                }
            }

            `when`("반려하면") {
                then("앱 imageUrl 은 HACCP 원본이고 사유가 남는다 — 승인했던 것도 반려로 내릴 수 있다") {
                    val id = seedProduct("RV-NO")
                    uploaded("RV-NO")
                    bulkRegister(listOf(item(id, "RV-NO")))
                    review(id, approved = true)

                    review(id, approved = false, reason = "연회색 배경 잔존").path("payload").path("status").asText() shouldBe "REJECTED"

                    row(id).let {
                        it["status"] shouldBe "REJECTED"
                        it["reason"] shouldBe "연회색 배경 잔존"
                        it["url"] shouldBe urlOf("RV-NO")
                    }
                    appImage(id) shouldBe httpsHaccp("RV-NO")

                    review(id, approved = true)
                    row(id)["reason"] shouldBe null
                }
            }

            `when`("관리자가 본 누끼 URL 을 함께 보냈는데 그사이 누끼가 다시 등록됐으면") {
                then("409 PFOOD-010 이고 상태를 바꾸지 않는다 — 같은 URL 이면 그대로 검수한다") {
                    val id = seedProduct("RV-EXP")
                    uploaded("RV-EXP")
                    bulkRegister(listOf(item(id, "RV-EXP")))
                    val seen = urlOf("RV-EXP")
                    val reuploadedAt = modifiedAt.plusSeconds(60)
                    uploaded("RV-EXP", reuploadedAt)
                    bulkRegister(listOf(item(id, "RV-EXP")))

                    review(id, approved = true, expected = 409, expectedCutoutImageUrl = seen).path("code").asText() shouldBe "PFOOD-010"
                    row(id).let {
                        it["status"] shouldBe "PENDING"
                        it["url"] shouldBe urlOf("RV-EXP", reuploadedAt)
                    }

                    review(id, approved = true, expectedCutoutImageUrl = urlOf("RV-EXP", reuploadedAt)).path("payload").path("status").asText() shouldBe "APPROVED"
                }
            }

            `when`("누끼가 없는 제품이거나 사유가 200자를 넘으면") {
                then("404 PFOOD-009 · 400 COMMON-002 다") {
                    val id = seedProduct("RV-NONE")
                    review(id, approved = true, expected = 404).path("code").asText() shouldBe "PFOOD-009"

                    uploaded("RV-NONE")
                    bulkRegister(listOf(item(id, "RV-NONE")))
                    review(id, approved = false, reason = "가".repeat(201), expected = 400).path("code").asText() shouldBe "COMMON-002"
                    row(id)["status"] shouldBe "PENDING"
                }
            }
        }

        given("누끼 검수 목록") {
            `when`("상태·경로·전경 비율로 거르면") {
                then("조건에 맞는 것만 id 순으로 원본·누끼 URL 과 함께 주고, 커서로 다음 쪽을 준다") {
                    val a = seedProduct("LS-A")
                    val b = seedProduct("LS-B")
                    val c = seedProduct("LS-C")
                    val d = seedProduct("LS-D")
                    seedProduct("LS-NOCUT")
                    listOf("LS-A", "LS-B", "LS-C", "LS-D").forEach { uploaded(it) }
                    bulkRegister(
                        listOf(
                            item(a, "LS-A", coverage = 0.9),
                            item(b, "LS-B", coverage = 0.3),
                            item(c, "LS-C", route = "fallback", coverage = 0.9),
                            item(d, "LS-D", coverage = 0.95),
                        ),
                    )
                    review(d, approved = true)

                    list("status=PENDING").path("items").map { it.path("processedFoodId").asLong() } shouldContainExactly listOf(a, b, c)
                    list("status=PENDING&route=vision&minCoverage=0.5").path("items").map { it.path("processedFoodId").asLong() } shouldContainExactly listOf(a)
                    list("maxCoverage=0.5").path("items").map { it.path("processedFoodId").asLong() } shouldContainExactly listOf(b)

                    val first = list("size=2")
                    first.path("items").map { it.path("processedFoodId").asLong() } shouldContainExactly listOf(a, b)
                    list("size=2&cursor=${first.path("nextCursor").asLong()}").let {
                        it.path("items").map { item -> item.path("processedFoodId").asLong() } shouldContainExactly listOf(c, d)
                        it.path("nextCursor").isNull shouldBe true
                    }

                    list("route=fallback").path("items").single().let {
                        it.path("reportNo").asText() shouldBe "LS-C"
                        it.path("originalImageUrl").asText() shouldBe httpsHaccp("LS-C")
                        it.path("cutoutImageUrl").asText() shouldBe urlOf("LS-C")
                        it.path("status").asText() shouldBe "PENDING"
                        it.path("route").asText() shouldBe "fallback"
                        it.path("coverage").asDouble() shouldBe 0.9
                    }
                }
            }
        }

        given("누끼 검수 목록 최근순 정렬·검색(KB-933)") {
            fun ids(page: JsonNode) = page.path("items").map { it.path("processedFoodId").asLong() }
            fun reviewedAt(id: Long, at: String) = execute("UPDATE processed_food_product SET cutout_reviewed_at = '$at' WHERE id = $id")
            fun registered(vararg pairs: Pair<String, String>): List<Long> {
                val ids = pairs.map { (reportNo, name) -> seedProduct(reportNo, name).also { uploaded(reportNo) } }
                bulkRegister(ids.zip(pairs.toList()).map { (id, pair) -> item(id, pair.first) })
                return ids
            }

            `when`("sort=RECENT 로 승인·반려 목록을 보면") {
                then("검수 시각 최신순(같으면 id 큰 순)이고 문자열 커서로 이어 받는다") {
                    val (old, tieLow, tieHigh, newest, pending) = registered("RC-1" to "최근1", "RC-2" to "최근2", "RC-3" to "최근3", "RC-4" to "최근4", "RC-5" to "최근5")
                    listOf(old, tieLow, tieHigh, newest).forEach { review(it, approved = true) }
                    reviewedAt(old, "2026-10-30 10:00:00")
                    reviewedAt(tieLow, "2026-10-30 11:00:00")
                    reviewedAt(tieHigh, "2026-10-30 11:00:00")
                    reviewedAt(newest, "2026-10-30 12:00:00")

                    val first = list("status=APPROVED&sort=RECENT&size=2")
                    ids(first) shouldContainExactly listOf(newest, tieHigh)
                    val cursor = first.path("nextCursor").asText()
                    first.path("nextCursor").isTextual shouldBe true

                    val second = list("status=APPROVED&sort=RECENT&size=2&cursor=$cursor")
                    ids(second) shouldContainExactly listOf(tieLow, old)
                    second.path("nextCursor").isNull shouldBe true
                    ids(list("status=APPROVED&sort=RECENT")).contains(pending) shouldBe false
                }
            }

            `when`("PENDING 이나 상태 없이 sort=RECENT 를 주면") {
                then("정렬을 무시하고 id 오름차순·숫자 커서다") {
                    val (a, b, c) = registered("RP-1" to "대기1", "RP-2" to "대기2", "RP-3" to "대기3")

                    val first = list("status=PENDING&sort=RECENT&size=2")
                    ids(first) shouldContainExactly listOf(a, b)
                    first.path("nextCursor").asText() shouldBe b.toString()
                    ids(list("sort=RECENT&size=2&cursor=${first.path("nextCursor").asText()}")) shouldContainExactly listOf(c)
                }
            }

            `when`("최근순 커서가 깨져 있으면") {
                then("400 FOOD-002 다") {
                    read(mockMvc.get("/api/admin/processed-foods/cutout-images?status=APPROVED&sort=RECENT&cursor=not.a.cursor") { admin(this) }, 400)
                        .path("code").asText() shouldBe "FOOD-002"
                }
            }

            `when`("q 로 검색하면") {
                then("제품명 부분 일치(대소문자 무시), 숫자만이면 품목보고번호·id 정확 일치도 찾고 다른 필터와 AND 다") {
                    val (choco, chocoMilk, numbered, other) = registered(
                        "QS-1" to "Choco Pie", "QS-2" to "초코우유 CHOCO", "20261030001" to "숫자번호칩", "QS-4" to "감자칩",
                    )
                    review(chocoMilk, approved = false, reason = "잘림")

                    ids(list("q=choco")) shouldContainExactly listOf(choco, chocoMilk)
                    ids(list("q=20261030001")) shouldContainExactly listOf(numbered)
                    ids(list("q=$other")) shouldContainExactly listOf(other)
                    ids(list("q=choco&status=PENDING")) shouldContainExactly listOf(choco)
                    ids(list("q=choco&status=REJECTED&sort=RECENT")) shouldContainExactly listOf(chocoMilk)
                    ids(read(mockMvc.get("/api/admin/processed-foods/cutout-images") { admin(this); param("q", "%") }, 200).path("payload")).size shouldBe 0
                }
            }
        }

        given("누끼 검수 현황 집계(KB-937)") {
            `when`("상태·경로별로 섞여 있고 오늘(KST)·어제 검수가 있으면") {
                then("전체·오늘 승인/반려와 경로별 수를 준다 — 오늘은 KST 자정 기준") {
                    val seoul = ZoneId.of("Asia/Seoul")
                    val todayStart = LocalDate.now(seoul).atStartOfDay(seoul).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
                    fun at(id: Long, time: LocalDateTime) = execute("UPDATE processed_food_product SET cutout_reviewed_at = '$time' WHERE id = $id")
                    val rows = listOf(
                        "SM-1" to "vision", "SM-2" to "vision", "SM-3" to "vision", "SM-4" to "vision",
                        "SM-5" to "fallback", "SM-6" to "fallback", "SM-7" to "fallback",
                    ).map { (reportNo, route) -> seedProduct(reportNo).also { uploaded(reportNo); bulkRegister(listOf(item(it, reportNo, route = route))) } }
                    val v1 = rows[0]
                    val v2 = rows[1]
                    val v3 = rows[2]
                    val f1 = rows[4]
                    val f2 = rows[5]
                    review(v1, approved = true)
                    review(v2, approved = true)
                    review(v3, approved = false, reason = "잘림")
                    review(f1, approved = true)
                    review(f2, approved = false, reason = "잔존")
                    at(v1, todayStart.plusMinutes(1))
                    at(v2, todayStart.minusSeconds(1))
                    at(v3, todayStart.plusHours(1))
                    at(f1, todayStart.minusDays(2))
                    at(f2, todayStart.plusSeconds(1))
                    val manual = seedProduct("SM-MANUAL").also { uploaded("SM-MANUAL") }
                    read(mockMvc.post("/api/admin/processed-foods/$manual/cutout-image/confirm") { admin(this, mapOf("key" to keyOf("SM-MANUAL"))) }, 200)

                    val summary = read(mockMvc.get("/api/admin/processed-foods/cutout-images/summary") { admin(this) }, 200).path("payload")

                    summary.path("pending").asLong() shouldBe 2
                    summary.path("approved").asLong() shouldBe 4
                    summary.path("rejected").asLong() shouldBe 2
                    summary.path("todayApproved").asLong() shouldBe 2
                    summary.path("todayRejected").asLong() shouldBe 2
                    summary.path("date").asText() shouldBe LocalDate.now(seoul).toString()
                    summary.path("byRoute").path("vision").let {
                        it.path("pending").asLong() shouldBe 1
                        it.path("approved").asLong() shouldBe 2
                        it.path("rejected").asLong() shouldBe 1
                        it.path("todayApproved").asLong() shouldBe 1
                        it.path("todayRejected").asLong() shouldBe 1
                    }
                    summary.path("byRoute").path("fallback").let {
                        it.path("pending").asLong() shouldBe 1
                        it.path("approved").asLong() shouldBe 1
                        it.path("rejected").asLong() shouldBe 1
                        it.path("todayApproved").asLong() shouldBe 0
                        it.path("todayRejected").asLong() shouldBe 1
                    }
                }
            }

            `when`("누끼가 하나도 없으면") {
                then("모두 0 이고 경로 칸도 0 으로 채운다") {
                    val summary = read(mockMvc.get("/api/admin/processed-foods/cutout-images/summary") { admin(this) }, 200).path("payload")
                    summary.path("pending").asLong() shouldBe 0
                    summary.path("byRoute").path("vision").path("approved").asLong() shouldBe 0
                    summary.path("byRoute").path("fallback").path("rejected").asLong() shouldBe 0
                }
            }
        }

        given("누끼 조건 일괄 승인") {
            `when`("전경 비율 기준을 주면") {
                then("PENDING·vision·기준 이상만 승인하고 건수를 준다 — dryRun 은 세기만 한다") {
                    val high = seedProduct("BA-HIGH")
                    val edge = seedProduct("BA-EDGE")
                    val low = seedProduct("BA-LOW")
                    val fallback = seedProduct("BA-FB")
                    val rejected = seedProduct("BA-REJ")
                    listOf("BA-HIGH", "BA-EDGE", "BA-LOW", "BA-FB", "BA-REJ").forEach { uploaded(it) }
                    bulkRegister(
                        listOf(
                            item(high, "BA-HIGH", coverage = 0.9),
                            item(edge, "BA-EDGE", coverage = 0.5),
                            item(low, "BA-LOW", coverage = 0.4999),
                            item(fallback, "BA-FB", route = "fallback", coverage = 0.9),
                            item(rejected, "BA-REJ", coverage = 0.9),
                        ),
                    )
                    review(rejected, approved = false, reason = "잘림")

                    bulkApprove(0.5, dryRun = true).let {
                        it.path("count").asLong() shouldBe 2
                        it.path("dryRun").asBoolean() shouldBe true
                    }
                    row(high)["status"] shouldBe "PENDING"

                    bulkApprove(0.5, dryRun = false).path("count").asLong() shouldBe 2

                    listOf(high, edge).forEach { id ->
                        row(id).let {
                            it["status"] shouldBe "APPROVED"
                            it["reviewedBy"] shouldBe adminId.toString()
                        }
                    }
                    row(low)["status"] shouldBe "PENDING"
                    row(fallback)["status"] shouldBe "PENDING"
                    row(rejected)["status"] shouldBe "REJECTED"
                    appImage(high) shouldBe urlOf("BA-HIGH")
                    appImage(low) shouldBe httpsHaccp("BA-LOW")
                }
            }
        }
    }
}

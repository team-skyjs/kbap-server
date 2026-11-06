package com.kbap.api.scan

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.port.llm.ExtractedMenu
import com.kbap.common.port.llm.MenuNameEstimation
import com.kbap.common.port.llm.MenuNameKind
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.sql.DataSource

@IntegrationTest
class MenuNameEstimateTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var vision: FakeMenuBoardVisionExtractor
    @Autowired private lateinit var estimator: FakeMenuNameEstimator
    @Autowired private lateinit var redis: StringRedisTemplate
    @Autowired private lateinit var taskExecutor: org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun longOf(sql: String): Long = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getLong(1) } } }
        fun seedMember(memberId: Long, avoid: List<String> = emptyList()) {
            execute(
                "INSERT INTO member (id, provider, provider_uid, member_status, onboarding_completed, avoidance_substance_codes, status, created_at, updated_at) " +
                    "VALUES ($memberId, 'GOOGLE', 'mne-$memberId', 'ACTIVE', 1, '${mapper.writeValueAsString(avoid)}', 'ACTIVE', NOW(6), NOW(6))",
            )
        }
        fun token(memberId: Long) = tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        fun ticket(memberId: Long): String = mapper.readTree(
            mockMvc.post("/api/scans/tickets") { header("Authorization", "Bearer ${token(memberId)}") }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload").path("ticket").asText()
        fun scan(memberId: Long, path: String): JsonNode = mapper.readTree(
            mockMvc.post("/api/scans") {
                param("lang", "en"); param("currency", "USD")
                header("Authorization", "Bearer ${token(memberId)}")
                header("X-API-Version", "2.0")
                header("X-Scan-Ticket", ticket(memberId))
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("imagePath" to path))
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")
        fun pending(memberId: Long, scanId: String, expected: Int = 200): JsonNode = mapper.readTree(
            mockMvc.get("/api/scans/$scanId/pending") {
                header("Authorization", "Bearer ${token(memberId)}")
                header("X-API-Version", "2.0")
            }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        fun pollUntilDone(memberId: Long, scanId: String): JsonNode {
            repeat(50) {
                val body = pending(memberId, scanId).path("payload")
                if (body.path("done").asBoolean()) return body
                Thread.sleep(100)
            }
            return pending(memberId, scanId).path("payload")
        }
        fun estimation(name: String, kind: MenuNameKind, codes: List<IngredientCode> = emptyList(), spiciness: Int = -1) =
            MenuNameEstimation(name, kind, if (kind == MenuNameKind.NOT_FOOD) null else "Eng $name", if (kind == MenuNameKind.NOT_FOOD) null else "About $name", codes, spiciness, 0.9)

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM menu_name_estimate")
            estimator.reset()
            redis.keys("menu-estimate:*").takeIf { it.isNotEmpty() }?.let { redis.delete(it) }
        }
        afterSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM menu_name_estimate")
        }

        given("메뉴판 스캔 v2 — DB 에 없는 메뉴의 LLM 추정(S-959)") {
            `when`("매칭·대체가 안 된 메뉴가 있으면") {
                then("응답에 scanId·pendingId 가 붙고, pending 이 완료된 항목을 원래 순서로 준다") {
                    seedMember(95901, avoid = listOf("BEEF"))
                    vision.program("scans/mne-1.jpg", listOf(ExtractedMenu("Galbitang", "추정갈비탕", 15000, null), ExtractedMenu("Store", "추정식당", null, null)))
                    estimator.answer = { names -> names.map { if (it == "추정식당") estimation(it, MenuNameKind.NOT_FOOD) else estimation(it, MenuNameKind.DISH, listOf(IngredientCode.BEEF), 1) } }

                    val payload = scan(95901, "scans/mne-1.jpg")
                    val scanId = payload.path("scanId").asText()
                    val pendingIds = payload.path("results").map { it.path("pendingId").asText() }

                    scanId.isNotBlank() shouldBe true
                    pendingIds.all { it.isNotBlank() } shouldBe true
                    val done = pollUntilDone(95901, scanId)
                    done.path("done").asBoolean() shouldBe true
                    done.path("items").map { it.path("pendingId").asText() } shouldBe pendingIds
                    done.path("items")[0].let {
                        it.path("kind").asText() shouldBe "DISH"
                        it.path("name").asText() shouldBe "Eng 추정갈비탕"
                        it.path("koreanName").asText() shouldBe "추정갈비탕"
                        it.path("avoidances").map { c -> c.asText() } shouldBe listOf("BEEF")
                        it.path("spiciness").asInt() shouldBe 1
                        it.path("aiEstimated").asBoolean() shouldBe true
                        it.path("filled").asBoolean() shouldBe false
                        it.path("existsNotReady").asBoolean() shouldBe true
                        it.path("foodId").asLong() shouldBe longOf("SELECT id FROM food WHERE korean_name = '추정갈비탕'")
                    }
                    done.path("items")[1].path("kind").asText() shouldBe "NOT_FOOD"
                    estimator.calls.size shouldBe 1
                }
            }

            `when`("같은 메뉴판을 다시 스캔하면") {
                then("캐시로 답해 LLM 호출이 0 이다") {
                    seedMember(95902)
                    vision.program("scans/mne-2.jpg", listOf(ExtractedMenu("Galbijjim", "추정갈비찜", 30000, null)))
                    estimator.answer = { names -> names.map { estimation(it, MenuNameKind.DISH) } }
                    pollUntilDone(95902, scan(95902, "scans/mne-2.jpg").path("scanId").asText())
                    estimator.calls.clear()

                    val again = pollUntilDone(95902, scan(95902, "scans/mne-2.jpg").path("scanId").asText())

                    again.path("items").single().path("kind").asText() shouldBe "DISH"
                    estimator.calls.size shouldBe 0
                    longOf("SELECT COUNT(*) FROM menu_name_estimate WHERE name_key = '추정갈비찜'") shouldBe 1
                }
            }

            `when`("어떤 추정이든") {
                then("riskLevel 은 SAFE 가 아니다 — 회피 재료와 겹치면 CAUTION, 아니면 UNKNOWN") {
                    seedMember(95903, avoid = listOf("BEEF"))
                    vision.program("scans/mne-3.jpg", listOf(ExtractedMenu("A", "추정소고기국", null, null), ExtractedMenu("B", "추정도토리묵", null, null), ExtractedMenu("C", "추정간판", null, null)))
                    estimator.answer = { names ->
                        names.map {
                            when (it) {
                                "추정소고기국" -> estimation(it, MenuNameKind.DISH, listOf(IngredientCode.BEEF))
                                "추정도토리묵" -> estimation(it, MenuNameKind.DISH, emptyList())
                                else -> estimation(it, MenuNameKind.NOT_FOOD)
                            }
                        }
                    }

                    val items = pollUntilDone(95903, scan(95903, "scans/mne-3.jpg").path("scanId").asText()).path("items")

                    items.map { it.path("riskLevel").asText() } shouldBe listOf("CAUTION", "UNKNOWN", "UNKNOWN")
                    items.none { it.path("riskLevel").asText() == "SAFE" } shouldBe true
                }
            }

            `when`("이름의 음식 행이 READY 가 아닌 채로 이미 있으면") {
                then("추정과 함께 existsNotReady·foodId 를 싣는다(어드민이 그 행을 검수로 보낼 수 있게)") {
                    seedMember(95904)
                    execute("INSERT INTO food (korean_name, description, spiciness, name_translations, description_translations, ingredients, content_status, status, created_at, updated_at) VALUES ('추정김치찌개', '', -1, '{}', '{}', '[]', 'FAILED', 'ACTIVE', NOW(6), NOW(6))")
                    val foodId = longOf("SELECT id FROM food WHERE korean_name = '추정김치찌개'")
                    vision.program("scans/mne-4.jpg", listOf(ExtractedMenu("Kimchi stew", "추정김치찌개", 9000, null)))
                    estimator.answer = { names -> names.map { estimation(it, MenuNameKind.DISH, listOf(IngredientCode.PORK), 3) } }

                    val item = pollUntilDone(95904, scan(95904, "scans/mne-4.jpg").path("scanId").asText()).path("items").single()

                    item.path("existsNotReady").asBoolean() shouldBe true
                    item.path("foodId").asLong() shouldBe foodId
                    item.path("kind").asText() shouldBe "DISH"
                    item.path("riskLevel").asText() shouldBe "UNKNOWN"
                    estimator.calls shouldBe listOf(listOf("추정김치찌개"))
                }
            }

            `when`("오늘 이름 상한을 다 썼으면") {
                then("LLM 을 부르지 않는다(현행 동작)") {
                    seedMember(95905)
                    redis.opsForValue().set("menu-estimate:" + LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE), "3000")
                    vision.program("scans/mne-5.jpg", listOf(ExtractedMenu("Odeng", "추정오뎅탕", null, null)))
                    estimator.answer = { names -> names.map { estimation(it, MenuNameKind.DISH) } }

                    val scanId = scan(95905, "scans/mne-5.jpg").path("scanId").asText()
                    Thread.sleep(500)

                    estimator.calls.size shouldBe 0
                    pending(95905, scanId).path("payload").path("items").size() shouldBe 0
                }
            }

            `when`("어드민이 그 사진의 스캔 결과를 보면") {
                then("미매칭 메뉴의 캐시된 추정(kind 포함)이 같이 나온다") {
                    seedMember(95908)
                    execute("INSERT INTO uploaded_image (member_id, object_path, content_type, size_bytes, status, created_at, updated_at) VALUES (95908, 'scans/mne-8.jpg', 'image/jpeg', 1024, 'ACTIVE', NOW(6), NOW(6))")
                    val imageId = longOf("SELECT id FROM uploaded_image WHERE object_path = 'scans/mne-8.jpg'")
                    vision.program("scans/mne-8.jpg", listOf(ExtractedMenu("Acorn jelly", "추정도토리묵무침", null, null), ExtractedMenu("Shop", "추정상호", null, null)))
                    estimator.answer = { names -> names.map { if (it == "추정상호") estimation(it, MenuNameKind.NOT_FOOD) else estimation(it, MenuNameKind.DISH) } }
                    pollUntilDone(95908, scan(95908, "scans/mne-8.jpg").path("scanId").asText())
                    com.kbap.api.BackgroundTasks.drain(taskExecutor)

                    val record = mapper.readTree(
                        mockMvc.get("/api/admin/scan-photos/$imageId/scan-results") {
                            header("X-API-Version", "3.2")
                            header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
                        }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
                    ).path("payload").path("records").single()

                    record.path("menuEstimates").map { it.path("name").asText() to it.path("kind").asText() } shouldBe listOf("추정도토리묵무침" to "DISH", "추정상호" to "NOT_FOOD")
                }
            }

            `when`("LLM 이 실패하면") {
                then("캐시에 쓰지 않고 실패 표시(1일)를 남겨 다음 스캔에서 다시 부르지 않는다") {
                    seedMember(95911)
                    vision.program("scans/mne-11.jpg", listOf(ExtractedMenu("X", "추정실패국", null, null)))
                    estimator.answer = { throw RuntimeException("timeout") }

                    val scanId = scan(95911, "scans/mne-11.jpg").path("scanId").asText()
                    repeat(30) { if (redis.hasKey("menu-estimate:fail:추정실패국")) return@repeat; Thread.sleep(100) }
                    estimator.calls.clear()
                    scan(95911, "scans/mne-11.jpg")
                    Thread.sleep(300)

                    redis.hasKey("menu-estimate:fail:추정실패국") shouldBe true
                    longOf("SELECT COUNT(*) FROM menu_name_estimate") shouldBe 0
                    estimator.calls.size shouldBe 0
                    pending(95911, scanId).path("payload").path("items").size() shouldBe 0
                }
            }

            `when`("응답에 묻지 않은 이름·형식이 틀린 항목·낮은 확신의 NOT_FOOD 가 섞이면") {
                then("이름으로 맞춰 유효한 것만 저장하고, 낮은 확신 NOT_FOOD 는 DISH 로 남긴다") {
                    seedMember(95912)
                    vision.program("scans/mne-12.jpg", listOf(ExtractedMenu("A", "추정정상탕", null, null), ExtractedMenu("B", "추정매운값", null, null), ExtractedMenu("C", "추정애매한줄", null, null)))
                    estimator.answer = { _ ->
                        listOf(
                            estimation("추정엉뚱한이름", MenuNameKind.DISH),
                            estimation("추정정상탕", MenuNameKind.DISH),
                            estimation("추정매운값", MenuNameKind.DISH, spiciness = 42),
                            MenuNameEstimation("추정애매한줄", MenuNameKind.NOT_FOOD, null, null, emptyList(), -1, 0.4),
                        )
                    }

                    pollUntilDone(95912, scan(95912, "scans/mne-12.jpg").path("scanId").asText())

                    longOf("SELECT COUNT(*) FROM menu_name_estimate WHERE name_key = '추정엉뚱한이름'") shouldBe 0
                    longOf("SELECT COUNT(*) FROM menu_name_estimate WHERE name_key = '추정매운값'") shouldBe 0
                    longOf("SELECT COUNT(*) FROM menu_name_estimate WHERE name_key = '추정정상탕' AND prompt_version = 'v1'") shouldBe 1
                    dataSource.connection.use { c -> c.createStatement().use { st -> st.executeQuery("SELECT kind FROM menu_name_estimate WHERE name_key = '추정애매한줄'").use { r -> r.next(); r.getString(1) } } } shouldBe "DISH"
                }
            }

            `when`("마감(15초)이 지났는데 추정이 없는 항목이 있으면") {
                then("그 항목을 kind=DISH·UNKNOWN 으로 확정하고 done=true 다") {
                    seedMember(95913)
                    vision.program("scans/mne-13.jpg", listOf(ExtractedMenu("Late", "추정늦은탕", null, null)))
                    estimator.answer = { emptyList() }
                    val scanId = scan(95913, "scans/mne-13.jpg").path("scanId").asText()
                    val key = "scan:$scanId"
                    val state = mapper.readTree(redis.opsForValue().get(key)) as com.fasterxml.jackson.databind.node.ObjectNode
                    state.put("deadlineMillis", System.currentTimeMillis() - 1)
                    redis.opsForValue().set(key, mapper.writeValueAsString(state))

                    val body = pending(95913, scanId).path("payload")

                    body.path("done").asBoolean() shouldBe true
                    body.path("items").single().let {
                        it.path("kind").asText() shouldBe "DISH"
                        it.path("riskLevel").asText() shouldBe "UNKNOWN"
                        it.path("name").asText() shouldBe "추정늦은탕"
                        it.path("filled").asBoolean() shouldBe true
                    }
                }
            }

            `when`("마감 뒤 확정한 다음에 추정이 늦게 캐시되면 — S-975(#565 Codex P2 의 '확정 응답 불변'을 의도적으로 바꿈)") {
                then("done 은 그대로 true 이고, 마감 때 채운 UNKNOWN 항목만 늦게 도착한 추정으로 바뀐다(상태 TTL 안)") {
                    seedMember(95917)
                    vision.program("scans/mne-17.jpg", listOf(ExtractedMenu("Late2", "추정늦은찜", null, null)))
                    estimator.answer = { emptyList() }
                    val scanId = scan(95917, "scans/mne-17.jpg").path("scanId").asText()
                    val key = "scan:$scanId"
                    val state = mapper.readTree(redis.opsForValue().get(key)) as com.fasterxml.jackson.databind.node.ObjectNode
                    state.put("deadlineMillis", System.currentTimeMillis() - 1)
                    redis.opsForValue().set(key, mapper.writeValueAsString(state))
                    val first = pending(95917, scanId).path("payload")

                    execute("INSERT INTO menu_name_estimate (name_key, kind, english_name, description, avoidance_codes, spiciness, confidence, model, prompt_version, status, created_at, updated_at) VALUES ('추정늦은찜', 'DRINK', 'Late', 'late', '[\"BEEF\"]', 2, 0.9, 'gpt-5-nano', 'v1', 'ACTIVE', NOW(6), NOW(6))")
                    val again = pending(95917, scanId).path("payload")

                    first.path("items").single().path("filled").asBoolean() shouldBe true
                    again.path("done").asBoolean() shouldBe true
                    again.path("items").single().let {
                        it.path("filled").asBoolean() shouldBe false
                        it.path("kind").asText() shouldBe "DRINK"
                        it.path("name").asText() shouldBe "Late"
                        it.path("pendingId").asText() shouldBe first.path("items").single().path("pendingId").asText()
                    }
                }
            }

            `when`("한 스캔의 미등록 이름이 여러 개면 — S-975") {
                then("호출당 4개씩 쪼개 동시에 부른다(한 호출에 몰아 타임아웃 나지 않게)") {
                    seedMember(95931)
                    val names = listOf("가", "나", "다", "라", "마", "바", "사", "아").map { "추정쪼개기$it" }
                    vision.program("scans/mne-31.jpg", names.map { ExtractedMenu("Dish", it, null, null) })
                    val gate = java.util.concurrent.CountDownLatch(1)
                    estimator.answer = { requested -> gate.await(10, java.util.concurrent.TimeUnit.SECONDS); requested.map { estimation(it, MenuNameKind.DISH) } }

                    val scanId = scan(95931, "scans/mne-31.jpg").path("scanId").asText()
                    repeat(100) { if (estimator.calls.size < 2) Thread.sleep(50) }

                    estimator.calls.size shouldBe 2
                    estimator.calls.map { it.size } shouldBe listOf(4, 4)
                    gate.countDown()
                    pollUntilDone(95931, scanId).path("items").map { it.path("filled").asBoolean() } shouldBe names.map { false }
                }
            }

            `when`("추정 호출이 타임아웃·연결 오류로 실패하면 — S-975") {
                then("하루가 아니라 10분만 다시 시도하지 않는다(실패 횟수도 세지 않는다)") {
                    seedMember(95932)
                    vision.program("scans/mne-32.jpg", listOf(ExtractedMenu("Down", "추정장애국", null, null)))
                    estimator.answer = { throw IllegalStateException("Request failed") }

                    scan(95932, "scans/mne-32.jpg")
                    repeat(40) { if (redis.hasKey("menu-estimate:fail:추정장애국") != true) Thread.sleep(50) }

                    val ttl = redis.getExpire("menu-estimate:fail:추정장애국")
                    (ttl in 1..600) shouldBe true
                    redis.hasKey("menu-estimate:fail-count:추정장애국") shouldBe false
                }
            }

            `when`("같은 이름을 다른 작업이 이미 추정 중이면(inflight) — Codex P2(#565)") {
                then("작업이 시작될 때 소유를 다시 확인해 LLM 을 부르지 않는다") {
                    seedMember(95918)
                    redis.opsForValue().set("menu-estimate:inflight:추정동시탕", "1", java.time.Duration.ofSeconds(30))
                    vision.program("scans/mne-18.jpg", listOf(ExtractedMenu("Busy", "추정동시탕", null, null)))
                    estimator.answer = { names -> names.map { estimation(it, MenuNameKind.DISH) } }

                    scan(95918, "scans/mne-18.jpg")
                    Thread.sleep(300)

                    estimator.calls.size shouldBe 0
                    redis.delete("menu-estimate:inflight:추정동시탕")
                }
            }

            `when`("봇 계정이 스캔하면") {
                then("LLM 을 부르지 않는다") {
                    seedMember(95914)
                    execute("UPDATE member SET is_bot = 1 WHERE id = 95914")
                    vision.program("scans/mne-14.jpg", listOf(ExtractedMenu("Bot", "추정봇메뉴", null, null)))
                    estimator.answer = { names -> names.map { estimation(it, MenuNameKind.DISH) } }

                    scan(95914, "scans/mne-14.jpg")
                    Thread.sleep(300)

                    estimator.calls.size shouldBe 0
                }
            }

            `when`("LLM 코드가 회원이 고른 하위 코드를 포함하면(SHRIMP ⊃ SALTED_SHRIMP)") {
                then("CAUTION 이다") {
                    seedMember(95915, avoid = listOf("SALTED_SHRIMP"))
                    vision.program("scans/mne-15.jpg", listOf(ExtractedMenu("Shrimp", "추정새우볶음", null, null)))
                    estimator.answer = { names -> names.map { estimation(it, MenuNameKind.DISH, listOf(IngredientCode.SHRIMP)) } }

                    pollUntilDone(95915, scan(95915, "scans/mne-15.jpg").path("scanId").asText()).path("items").single().path("riskLevel").asText() shouldBe "CAUTION"
                }
            }

            `when`("추정 재료가 회원 기피와 겹치지 않으면 — QA(SHRIMP 회원·올갱이국 MOLLUSK)") {
                then("avoidances 는 빈 배열·riskLevel UNKNOWN 이고, 캐시에는 추정 코드 전체가 남는다") {
                    seedMember(95917, avoid = listOf("SHRIMP"))
                    vision.program("scans/mne-17.jpg", listOf(ExtractedMenu("Olgaengi", "추정올갱이국", null, null)))
                    estimator.answer = { names -> names.map { estimation(it, MenuNameKind.DISH, listOf(IngredientCode.MOLLUSK)) } }

                    pollUntilDone(95917, scan(95917, "scans/mne-17.jpg").path("scanId").asText()).path("items").single().let {
                        it.path("avoidances").map { c -> c.asText() } shouldBe emptyList()
                        it.path("riskLevel").asText() shouldBe "UNKNOWN"
                    }
                    longOf("SELECT COUNT(*) FROM menu_name_estimate WHERE name_key = '추정올갱이국' AND JSON_CONTAINS(avoidance_codes, '\"MOLLUSK\"')") shouldBe 1
                }
            }

            `when`("술 이름의 DRINK 추정이 ALCOHOL 코드 없이 캐시돼 있으면 — S-973(운영 소주·맥주)") {
                then("읽을 때 ALCOHOL 을 더해 알코올 회피 회원에게 CAUTION 이고, 생성 사진 대신 공용 술 이미지를 주며, 저장 행은 그대로다") {
                    seedMember(95919, avoid = listOf("ALCOHOL"))
                    listOf("추정참이슬", "추정오미자에이드").forEach { name ->
                        execute(
                            "INSERT INTO menu_name_estimate (name_key, kind, english_name, description, avoidance_codes, spiciness, confidence, model, prompt_version, image_ref, image_generated_at, status, created_at, updated_at) " +
                                "VALUES ('$name', 'DRINK', 'Eng', 'About', '[]', -1, 0.900, 'm', 'p', 'images/webp/menu-estimate/$name.webp', NOW(6), 'ACTIVE', NOW(6), NOW(6))",
                        )
                    }
                    vision.program("scans/mne-19.jpg", listOf(ExtractedMenu("Soju", "추정참이슬", null, null), ExtractedMenu("Ade", "추정오미자에이드", null, null)))

                    val items = pollUntilDone(95919, scan(95919, "scans/mne-19.jpg").path("scanId").asText()).path("items")

                    items[0].let {
                        it.path("estimatedCodes").map { c -> c.asText() } shouldBe listOf("ALCOHOL")
                        it.path("avoidances").map { c -> c.asText() } shouldBe listOf("ALCOHOL")
                        it.path("riskLevel").asText() shouldBe "CAUTION"
                        it.path("imageUrl").asText().endsWith("images/webp/static/alcohol-category.webp") shouldBe true
                        it.path("aiGeneratedImage").asBoolean() shouldBe false
                    }
                    items[1].let {
                        it.path("estimatedCodes").map { c -> c.asText() } shouldBe emptyList()
                        it.path("riskLevel").asText() shouldBe "UNKNOWN"
                        it.path("imageUrl").asText().endsWith("menu-estimate/추정오미자에이드.webp") shouldBe true
                    }
                    longOf("SELECT COUNT(*) FROM menu_name_estimate WHERE name_key = '추정참이슬' AND JSON_LENGTH(avoidance_codes) = 0") shouldBe 1
                    estimator.calls.size shouldBe 0
                }
            }

            `when`("이름에 무알코올·논알콜·비알콜·제로가 있는 술 이름 DRINK 추정이면 — S-973 Codex P2") {
                then("ALCOHOL 을 더하지 않고 생성 사진 규칙 그대로다") {
                    seedMember(95920, avoid = listOf("ALCOHOL"))
                    val names = listOf("추정무알코올맥주", "추정논알콜맥주", "추정비알콜와인", "추정카스제로")
                    names.forEach { name ->
                        execute(
                            "INSERT INTO menu_name_estimate (name_key, kind, english_name, description, avoidance_codes, spiciness, confidence, model, prompt_version, image_ref, image_generated_at, status, created_at, updated_at) " +
                                "VALUES ('$name', 'DRINK', 'Eng', 'About', '[]', -1, 0.900, 'm', 'p', 'images/webp/menu-estimate/$name.webp', NOW(6), 'ACTIVE', NOW(6), NOW(6))",
                        )
                    }
                    vision.program("scans/mne-20.jpg", names.map { ExtractedMenu("NA", it, null, null) })

                    pollUntilDone(95920, scan(95920, "scans/mne-20.jpg").path("scanId").asText()).path("items").forEachIndexed { i, it ->
                        it.path("estimatedCodes").map { c -> c.asText() } shouldBe emptyList()
                        it.path("riskLevel").asText() shouldBe "UNKNOWN"
                        it.path("imageUrl").asText().endsWith("menu-estimate/${names[i]}.webp") shouldBe true
                    }
                }
            }

            `when`("이름의 제로가 제로슈거·제로칼로리 뜻이면 — 보안 검토(무알코올 제외어 과대 적용)") {
                then("술로 보고 ALCOHOL 을 더한다(제로슈거 소주는 술이다)") {
                    seedMember(95921, avoid = listOf("ALCOHOL"))
                    val names = listOf("추정제로슈거소주", "추정제로칼로리하이볼")
                    names.forEach { name ->
                        execute(
                            "INSERT INTO menu_name_estimate (name_key, kind, english_name, description, avoidance_codes, spiciness, confidence, model, prompt_version, status, created_at, updated_at) " +
                                "VALUES ('$name', 'DRINK', 'Eng', 'About', '[]', -1, 0.900, 'm', 'p', 'ACTIVE', NOW(6), NOW(6))",
                        )
                    }
                    vision.program("scans/mne-21.jpg", names.map { ExtractedMenu("Zero", it, null, null) })

                    pollUntilDone(95921, scan(95921, "scans/mne-21.jpg").path("scanId").asText()).path("items").forEach {
                        it.path("estimatedCodes").map { c -> c.asText() } shouldBe listOf("ALCOHOL")
                        it.path("riskLevel").asText() shouldBe "CAUTION"
                    }
                }
            }

            `when`("추정 재료가 회원 기피와 겹치면") {
                then("겹치는 추정 코드만 avoidances 로 내리고 riskLevel CAUTION 이다") {
                    seedMember(95918, avoid = listOf("MOLLUSK"))
                    vision.program("scans/mne-18.jpg", listOf(ExtractedMenu("Gizzard", "추정연체볶음", null, null)))
                    estimator.answer = { names -> names.map { estimation(it, MenuNameKind.DISH, listOf(IngredientCode.MOLLUSK, IngredientCode.WHEAT, IngredientCode.EGG)) } }

                    pollUntilDone(95918, scan(95918, "scans/mne-18.jpg").path("scanId").asText()).path("items").single().let {
                        it.path("avoidances").map { c -> c.asText() } shouldBe listOf("MOLLUSK")
                        it.path("riskLevel").asText() shouldBe "CAUTION"
                    }
                }
            }

            `when`("어드민이 추정 캐시를 지우면") {
                then("다음 스캔에서 다시 추정한다") {
                    seedMember(95916)
                    vision.program("scans/mne-16.jpg", listOf(ExtractedMenu("Re", "추정재추정국", null, null)))
                    estimator.answer = { names -> names.map { estimation(it, MenuNameKind.DISH) } }
                    pollUntilDone(95916, scan(95916, "scans/mne-16.jpg").path("scanId").asText())
                    estimator.calls.clear()

                    mapper.readTree(
                        mockMvc.delete("/api/admin/menu-name-estimates/추정재추정국") {
                            header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
                            header("X-API-Version", "1.0")
                        }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
                    ).path("payload").path("deleted").asBoolean() shouldBe true
                    pollUntilDone(95916, scan(95916, "scans/mne-16.jpg").path("scanId").asText())

                    estimator.calls shouldBe listOf(listOf("추정재추정국"))
                }
            }

            `when`("모르는 scanId 거나 남의 scanId 면") {
                then("404 SCAN-014 다") {
                    seedMember(95906)
                    seedMember(95907)
                    vision.program("scans/mne-6.jpg", listOf(ExtractedMenu("Soju", "추정소주", null, null)))
                    estimator.answer = { names -> names.map { estimation(it, MenuNameKind.DRINK) } }
                    val scanId = scan(95906, "scans/mne-6.jpg").path("scanId").asText()

                    pending(95906, "no-such-scan", 404).path("code").asText() shouldBe "SCAN-014"
                    pending(95907, scanId, 404).path("code").asText() shouldBe "SCAN-014"
                }
            }
        }
    }
}

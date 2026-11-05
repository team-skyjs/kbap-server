package com.kbap.api.chat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.notification.FakePushClient
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class ChatControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var pushClient: FakePushClient
    @Autowired private lateinit var alerts: FakeOperatorAlertSender
    @Autowired private lateinit var redis: StringRedisTemplate

    private val mapper = jacksonObjectMapper()

    init {
        val adminId = 97201L
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun longOf(sql: String): Long = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getLong(1) } } }
        fun stringOf(sql: String): String? = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getString(1) } } }
        fun seedMember(id: Long, nickname: String = "nick$id") = execute(
            "INSERT INTO member (id, provider, provider_uid, member_status, onboarding_completed, avoidance_substance_codes, nickname, country_code, status, created_at, updated_at) " +
                "VALUES ($id, 'GOOGLE', 'chat-$id', 'ACTIVE', 1, '[]', '$nickname', 'US', 'ACTIVE', NOW(6), NOW(6))",
        )
        fun seedDevice(installationId: String, memberId: Long?) = execute(
            "INSERT INTO notification_device (installation_id, member_id, expo_token, platform, lang, status, created_at, updated_at) " +
                "VALUES ('$installationId', ${memberId ?: "NULL"}, 'ExponentPushToken[$installationId]', 'IOS', 'en', 'ACTIVE', NOW(6), NOW(6))",
        )
        fun MockHttpServletRequestDsl.as_(memberId: Long?, installationId: String?) {
            header("X-API-Version", "1.0")
            installationId?.let { header("X-Installation-Id", it) }
            memberId?.let { header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(it, MemberRole.USER)}") }
        }
        fun MockHttpServletRequestDsl.asAdmin(target: String? = "dev") {
            header("X-API-Version", "1.0")
            header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(adminId, MemberRole.ADMIN)}")
            target?.let { header("X-Target-Environment", it) }
        }
        fun body(raw: String): JsonNode = mapper.readTree(raw.ifBlank { "{}" })
        fun send(memberId: Long?, installationId: String?, text: String, deviceInfo: Map<String, String>? = null): Pair<Int, JsonNode> =
            mockMvc.post("/api/chat/messages") {
                as_(memberId, installationId)
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("content" to text, "deviceInfo" to deviceInfo))
            }.andReturn().response.let { it.status to body(it.getContentAsString(Charsets.UTF_8)) }
        fun messages(memberId: Long?, installationId: String?, query: String = ""): JsonNode =
            body(mockMvc.get("/api/chat/messages$query") { as_(memberId, installationId) }.andReturn().response.getContentAsString(Charsets.UTF_8)).path("payload")
        fun unread(memberId: Long?, installationId: String?): JsonNode =
            body(mockMvc.get("/api/chat/unread") { as_(memberId, installationId) }.andReturn().response.getContentAsString(Charsets.UTF_8)).path("payload")
        fun reply(conversationId: Long, text: String, target: String? = "dev"): Pair<Int, JsonNode> =
            mockMvc.post("/api/admin/chats/$conversationId/messages") {
                asAdmin(target)
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("content" to text))
            }.andReturn().response.let { it.status to body(it.getContentAsString(Charsets.UTF_8)) }
        fun open(payload: Map<String, Any?>, target: String? = "dev"): Pair<Int, JsonNode> =
            mockMvc.post("/api/admin/chats") {
                asAdmin(target)
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(payload)
            }.andReturn().response.let { it.status to body(it.getContentAsString(Charsets.UTF_8)) }
        fun adminGet(path: String): JsonNode = body(mockMvc.get(path) { asAdmin(null) }.andReturn().response.getContentAsString(Charsets.UTF_8)).path("payload")
        fun waitUntil(condition: () -> Boolean) {
            repeat(50) { if (condition()) return; Thread.sleep(100) }
        }

        beforeEach {
            TestTables.clearAll(dataSource)
            listOf("feedback_reply", "feedback").forEach { execute("DELETE FROM $it") }
            execute("INSERT IGNORE INTO admin_account (id, admin_id, admin_pwd) VALUES ($adminId, 'chat-admin-2', 'x')")
            pushClient.reset()
            alerts.sent.clear()
            redis.keys("chat:alert:*").takeIf { it.isNotEmpty() }?.let { redis.delete(it) }
            redis.keys("*chat*").filter { it.contains("quota") }.takeIf { it.isNotEmpty() }?.let { redis.delete(it) }
        }
        afterSpec {
            TestTables.clearAll(dataSource)
            listOf("feedback_reply", "feedback").forEach { execute("DELETE FROM $it") }
        }

        given("앱 채팅 — S-971") {
            `when`("게스트가 처음 메시지를 보내면") {
                then("대화방이 생기고 내 메시지가 목록에 보이며, 운영진에게 텔레그램 알림이 한 번 간다(1분 안 연속 메시지는 묶음)") {
                    send(null, "inst-g1", "안녕하세요", mapOf("lang" to "ja", "locale" to "ja-JP", "secret" to "x")).first shouldBe 201
                    send(null, "inst-g1", "두 번째").first shouldBe 201

                    messages(null, "inst-g1").let {
                        it.path("conversationId").isNull shouldBe false
                        it.path("items").map { m -> m.path("content").asText() } shouldBe listOf("두 번째", "안녕하세요")
                        it.path("items")[0].path("authorRole").asText() shouldBe "USER"
                        it.path("hasMore").asBoolean() shouldBe false
                    }
                    waitUntil { alerts.sent.isNotEmpty() }
                    Thread.sleep(300)
                    alerts.sent.size shouldBe 1
                    alerts.sent.single().contains("게스트 : 안녕하세요") shouldBe true
                    stringOf("SELECT JSON_EXTRACT(last_device_info, '$.secret') FROM chat_conversation WHERE guest_installation_id = 'inst-g1'") shouldBe null
                }
            }

            `when`("방이 없는 사용자가 조회하면") {
                then("conversationId=null·빈 목록, 안읽음 0") {
                    messages(null, "inst-none").let {
                        it.path("conversationId").isNull shouldBe true
                        it.path("items").size() shouldBe 0
                    }
                    unread(null, "inst-none").path("unreadCount").asLong() shouldBe 0
                }
            }

            `when`("운영진이 답하면") {
                then("사용자 안읽음 1·미리보기, 그 기기에 OPERATOR_CHAT 푸시(딥링크 conversationId), 읽음 처리 뒤 0") {
                    seedMember(97211)
                    seedDevice("inst-m1", 97211)
                    send(97211, "inst-m1", "질문이요").first shouldBe 201
                    val conversationId = messages(97211, "inst-m1").path("conversationId").asLong()

                    reply(conversationId, "답변드려요").first shouldBe 200

                    unread(97211, "inst-m1").let {
                        it.path("unreadCount").asLong() shouldBe 1
                        it.path("lastOperatorMessage").path("content").asText() shouldBe "답변드려요"
                    }
                    waitUntil { pushClient.sent.isNotEmpty() }
                    pushClient.sent.single().let {
                        it.data["type"] shouldBe "OPERATOR_CHAT"
                        (it.data["conversationId"] as Number).toLong() shouldBe conversationId
                        it.body shouldBe "답변드려요"
                        it.title shouldBe "K-Bap Team"
                    }
                    val latest = messages(97211, "inst-m1").path("items")[0].path("id").asLong()
                    mockMvc.post("/api/chat/read") {
                        as_(97211, "inst-m1")
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"lastMessageId":$latest}"""
                    }.andExpect { status { isOk() } }
                    unread(97211, "inst-m1").path("unreadCount").asLong() shouldBe 0
                }
            }

            `when`("게스트 방에 운영진이 답하면") {
                then("그 게스트 기기로 푸시가 간다") {
                    seedDevice("inst-g2", null)
                    send(null, "inst-g2", "게스트 질문").first shouldBe 201
                    val conversationId = messages(null, "inst-g2").path("conversationId").asLong()

                    reply(conversationId, "게스트 답변").first shouldBe 200

                    waitUntil { pushClient.sent.isNotEmpty() }
                    pushClient.sent.single().to shouldBe "ExponentPushToken[inst-g2]"
                }
            }

            `when`("게스트로 대화하던 기기에서 로그인해도 — Codex P1(#584)") {
                then("게스트 방을 계정으로 넘겨받지 않는다(기기 id 는 기기 소유 증명이 아니다) — 회원은 자기 방, 게스트 방은 그 기기에 그대로") {
                    seedMember(97212)
                    seedDevice("inst-g3", 97212)
                    send(null, "inst-g3", "로그인 전").first shouldBe 201
                    val guestConversation = messages(null, "inst-g3").path("conversationId").asLong()

                    messages(97212, "inst-g3").path("conversationId").isNull shouldBe true
                    send(97212, "inst-g3", "로그인 후").first shouldBe 201

                    messages(97212, "inst-g3").path("items").map { it.path("content").asText() } shouldBe listOf("로그인 후")
                    messages(null, "inst-g3").let {
                        it.path("conversationId").asLong() shouldBe guestConversation
                        it.path("items").map { m -> m.path("content").asText() } shouldBe listOf("로그인 전")
                    }
                    longOf("SELECT COUNT(*) FROM chat_conversation WHERE id = $guestConversation AND member_id IS NULL AND guest_installation_id = 'inst-g3'") shouldBe 1
                }
            }

            `when`("게스트 방에 운영진 답장이 있는 기기에서 로그인한 회원이 안읽음을 보면 — Codex P2(#584)") {
                then("회원 방만 보고(방 없으면 0·null) 게스트 방 안읽음을 가리키지 않는다") {
                    seedMember(97215)
                    send(null, "inst-g8", "게스트 질문").first shouldBe 201
                    reply(messages(null, "inst-g8").path("conversationId").asLong(), "게스트에게 답변").first shouldBe 200
                    unread(null, "inst-g8").path("unreadCount").asLong() shouldBe 1

                    unread(97215, "inst-g8").let {
                        it.path("unreadCount").asLong() shouldBe 0
                        it.path("lastOperatorMessage").isNull shouldBe true
                    }
                }
            }

            `when`("다른 사람의 게스트 기기 id 를 헤더에 넣고 로그인·기기 연결까지 한 회원이 조회해도 — Codex P1(#584)") {
                then("그 게스트 방을 넘겨받지 못하고 게스트는 계속 자기 대화를 본다") {
                    seedMember(97214)
                    send(null, "inst-victim", "남의 대화").first shouldBe 201
                    val victimConversation = messages(null, "inst-victim").path("conversationId").asLong()
                    seedDevice("inst-victim", 97214)

                    messages(97214, "inst-victim").path("conversationId").isNull shouldBe true
                    send(97214, "inst-victim", "내 대화").first shouldBe 201

                    longOf("SELECT COUNT(*) FROM chat_conversation WHERE id = $victimConversation AND member_id IS NULL AND guest_installation_id = 'inst-victim'") shouldBe 1
                    messages(null, "inst-victim").path("items").map { it.path("content").asText() } shouldBe listOf("남의 대화")
                }
            }

            `when`("24시간 안에 100건을 보낸 기기가 또 보내면") {
                then("429 CHAT-001") {
                    send(null, "inst-g5", "첫 메시지").first shouldBe 201
                    val conversationId = messages(null, "inst-g5").path("conversationId").asLong()
                    execute(
                        "INSERT INTO chat_message (conversation_id, author_role, content, status, created_at, updated_at) " +
                            "SELECT $conversationId, 'USER', 'x', 'ACTIVE', NOW(6), NOW(6) FROM information_schema.COLUMNS LIMIT 99",
                    )

                    send(null, "inst-g5", "한도 초과").let { (status, body) ->
                        status shouldBe 429
                        body.path("code").asText() shouldBe "CHAT-001"
                    }
                }
            }

            `when`("메시지가 limit 보다 많으면") {
                then("최신부터 limit 건, nextCursor 를 before 로 넘기면 그 이전") {
                    (1..3).forEach { send(null, "inst-g6", "m$it").first shouldBe 201 }

                    val first = messages(null, "inst-g6", "?limit=2")
                    first.path("items").map { it.path("content").asText() } shouldBe listOf("m3", "m2")
                    first.path("hasMore").asBoolean() shouldBe true
                    messages(null, "inst-g6", "?limit=2&before=${first.path("nextCursor").asText()}").path("items").map { it.path("content").asText() } shouldBe listOf("m1")
                }
            }

            `when`("구 앱이 문의(POST /api/feedbacks)를 보내고 운영진이 구 어드민으로 답하면") {
                then("같은 대화방에 legacyFeedbackId 가 달린 메시지로 함께 쌓이고, 어드민 문의 상세에 conversationId 가 실린다") {
                    mockMvc.post("/api/feedbacks") {
                        as_(null, "inst-g7")
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"content":"구 문의"}"""
                    }.andExpect { status { isCreated() } }
                    val feedbackId = longOf("SELECT id FROM feedback WHERE installation_id = 'inst-g7'")
                    mockMvc.post("/api/admin/feedbacks/$feedbackId/replies") {
                        asAdmin(null)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"content":"구 답변"}"""
                    }.andExpect { status { is2xxSuccessful() } }

                    messages(null, "inst-g7").path("items").map { it.path("content").asText() to it.path("legacyFeedbackId").asLong() } shouldBe
                        listOf("구 답변" to feedbackId, "구 문의" to feedbackId)
                    val conversationId = messages(null, "inst-g7").path("conversationId").asLong()
                    adminGet("/api/admin/chats/by-feedback/$feedbackId").path("conversationId").asLong() shouldBe conversationId
                    adminGet("/api/admin/feedbacks/$feedbackId").path("conversationId").asLong() shouldBe conversationId
                }
            }
        }

        given("어드민 채팅 — S-971") {
            `when`("대화 목록을 보면") {
                then("안읽음 있는 방이 먼저, 국가·언어·안읽음 수·마지막 메시지가 실리고 배지 합계가 맞는다") {
                    seedMember(97221, nickname = "카이")
                    send(97221, "inst-a1", "회원 메시지", mapOf("lang" to "en")).first shouldBe 201
                    val memberConversation = messages(97221, "inst-a1").path("conversationId").asLong()
                    reply(memberConversation, "읽음 처리된 셈").first shouldBe 200
                    send(null, "inst-a2", "게스트 메시지", mapOf("locale" to "th-TH")).first shouldBe 201
                    send(null, "inst-a2", "게스트 둘째").first shouldBe 201

                    val page = adminGet("/api/admin/chats?size=1")
                    page.path("hasMore").asBoolean() shouldBe true
                    page.path("items").single().let {
                        it.path("installationId").asText() shouldBe "inst-a2"
                        it.path("unreadCount").asLong() shouldBe 2
                        it.path("country").asText() shouldBe "TH"
                        it.path("language").asText() shouldBe "th"
                        it.path("lastMessage").path("content").asText() shouldBe "게스트 둘째"
                    }
                    adminGet("/api/admin/chats?size=1&cursor=${page.path("nextCursor").asText()}").path("items").single().let {
                        it.path("memberId").asLong() shouldBe 97221
                        it.path("nickname").asText() shouldBe "카이"
                        it.path("country").asText() shouldBe "US"
                        it.path("language").asText() shouldBe "en"
                        it.path("unreadCount").asLong() shouldBe 0
                    }
                    adminGet("/api/admin/chats/unread").let {
                        it.path("conversations").asLong() shouldBe 1
                        it.path("messages").asLong() shouldBe 2
                    }
                    val guestConversation = adminGet("/api/admin/chats?size=1").path("items")[0].path("conversationId").asLong()
                    val latest = adminGet("/api/admin/chats/$guestConversation/messages").path("messages").path("items")[0].path("id").asLong()
                    mockMvc.post("/api/admin/chats/$guestConversation/read") {
                        asAdmin(null)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"lastMessageId":$latest}"""
                    }.andExpect { status { isOk() } }
                    adminGet("/api/admin/chats/unread").path("messages").asLong() shouldBe 0
                }
            }

            `when`("회원 id 로 대화방을 열고 먼저 보내면") {
                then("없으면 만들고(created=true) 다시 열면 같은 id, 그 회원 앱 목록에 운영진 메시지가 보인다") {
                    seedMember(97222)
                    val (status, body) = open(mapOf("memberId" to 97222))
                    status shouldBe 200
                    body.path("payload").path("created").asBoolean() shouldBe true
                    val conversationId = body.path("payload").path("conversationId").asLong()
                    open(mapOf("memberId" to 97222)).second.path("payload").let {
                        it.path("conversationId").asLong() shouldBe conversationId
                        it.path("created").asBoolean() shouldBe false
                    }

                    reply(conversationId, "How can we help you?").first shouldBe 200

                    messages(97222, "inst-a3").path("items").single().path("authorRole").asText() shouldBe "OPERATOR"
                }
            }

            `when`("대화방 열기 요청이 잘못되면") {
                then("둘 다/둘 다 없음 400 CHAT-005, 없는 회원 404 CHAT-004, 환경 불일치 409 CHAT-003, 헤더 누락 400") {
                    open(mapOf("memberId" to 1, "installationId" to "x")).second.path("code").asText() shouldBe "CHAT-005"
                    open(emptyMap()).second.path("code").asText() shouldBe "CHAT-005"
                    open(mapOf("memberId" to 987_654)).let { (status, body) ->
                        status shouldBe 404
                        body.path("code").asText() shouldBe "CHAT-004"
                    }
                    open(mapOf("installationId" to "inst-a4"), target = "prod").let { (status, body) ->
                        status shouldBe 409
                        body.path("code").asText() shouldBe "CHAT-003"
                    }
                    open(mapOf("installationId" to "inst-a4"), target = null).first shouldBe 400
                    longOf("SELECT COUNT(*) FROM chat_conversation WHERE guest_installation_id = 'inst-a4'") shouldBe 0
                    reply(987_654, "없는 방").let { (status, body) ->
                        status shouldBe 404
                        body.path("code").asText() shouldBe "CHAT-002"
                    }
                }
            }
        }
    }
}

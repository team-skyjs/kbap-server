package com.kbap.api.notification

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.auth.FakeSocialTokenVerifier
import com.kbap.common.domain.notification.NotificationJpaRepository
import com.kbap.common.domain.notification.model.Notification
import com.kbap.common.domain.notification.model.NotificationType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.ZoneId
import javax.sql.DataSource

@IntegrationTest
class NotificationInboxTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var verifier: FakeSocialTokenVerifier

    @Autowired
    private lateinit var notificationRepository: NotificationJpaRepository

    init {
        val objectMapper = jacksonObjectMapper()
        val INSTALLATION = "00000000-0000-0000-0000-0000000inbox"

        fun memberIdOf(providerUid: String): Long =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT id FROM member WHERE provider_uid = ?").use { ps ->
                    ps.setString(1, providerUid)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else error("회원 없음: $providerUid") }
                }
            }

        fun login(sub: String): Pair<Long, String> {
            val response = mockMvc.post("/api/auth/login") {
                header("X-API-Version", "1.1")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("idToken" to sub))
            }.andReturn().response
            response.status shouldBe 200
            val accessToken = objectMapper.readTree(response.contentAsString).path("payload").path("accessToken").asText()
            return memberIdOf(sub) to accessToken
        }

        fun seed(
            memberId: Long,
            title: String,
            type: NotificationType = NotificationType.NOTICE,
            installationId: String = INSTALLATION,
        ): Notification =
            notificationRepository.save(Notification.forMemberDevice(memberId, installationId, type, title, "본문 $title", null))

        fun setCreatedAt(id: Long, createdAt: LocalDateTime) {
            dataSource.connection.use { c ->
                c.prepareStatement("UPDATE notification SET created_at = ? WHERE id = ?").use { ps ->
                    ps.setTimestamp(1, Timestamp.valueOf(createdAt))
                    ps.setLong(2, id)
                    ps.executeUpdate()
                }
            }
        }

        fun readAtOf(id: Long): Timestamp? =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT read_at FROM notification WHERE id = ?").use { ps ->
                    ps.setLong(1, id)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp(1) else error("알림 없음: $id") }
                }
            }

        fun createdAtOf(id: Long): LocalDateTime =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT created_at FROM notification WHERE id = ?").use { ps ->
                    ps.setLong(1, id)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp(1).toLocalDateTime() else error("알림 없음: $id") }
                }
            }

        fun list(accessToken: String?, installationId: String? = INSTALLATION): MockHttpServletResponse =
            mockMvc.get("/api/notifications") {
                header("X-API-Version", "1.0")
                if (accessToken != null) header("Authorization", "Bearer $accessToken")
                if (installationId != null) header("X-Installation-Id", installationId)
            }.andReturn().response

        fun read(accessToken: String?, notificationId: Long, installationId: String? = INSTALLATION): MockHttpServletResponse =
            mockMvc.patch("/api/notifications/$notificationId/read") {
                header("X-API-Version", "1.0")
                if (accessToken != null) header("Authorization", "Bearer $accessToken")
                if (installationId != null) header("X-Installation-Id", installationId)
            }.andReturn().response

        fun payload(response: MockHttpServletResponse): JsonNode {
            response.status shouldBe 200
            return objectMapper.readTree(response.contentAsString).path("payload")
        }

        fun codeOf(response: MockHttpServletResponse): String =
            objectMapper.readTree(response.contentAsString).path("code").asText()

        fun epochMillisOf(dateTime: LocalDateTime): Long =
            dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        beforeContainer {
            TestTables.clearAll(dataSource)
            verifier.reset()
        }

        given("최근 7일 알림 목록") {
            `when`("알림이 없는 회원이 조회하면") {
                then("빈 목록을 받는다") {
                    val (_, token) = login("inbox-empty")

                    val body = payload(list(token))

                    body.isArray shouldBe true
                    body.size() shouldBe 0
                }
            }

            `when`("7일 이내 알림 5건과 8일 전 알림 2건이 있으면") {
                then("7일 이내 5건만 id 역순으로 온다") {
                    val (memberId, token) = login("inbox-recent")
                    val recent = (1..5).map { seed(memberId, "recent-$it") }
                    val old = (1..2).map { seed(memberId, "old-$it") }
                    old.forEach { setCreatedAt(it.id, LocalDateTime.now().minusDays(8)) }

                    val body = payload(list(token))

                    body.map { it.path("id").asLong() } shouldBe recent.map { it.id }.sortedDescending()
                }
            }

            `when`("종류가 섞인 알림이 있으면") {
                then("종류 구분 없이 시간 역순으로 오고 수신 시각은 epoch 밀리초다") {
                    val (memberId, token) = login("inbox-mixed")
                    val helpful = seed(memberId, "helpful", NotificationType.HELPFUL)
                    val notice = seed(memberId, "notice", NotificationType.NOTICE)
                    val suggestion = seed(memberId, "suggestion", NotificationType.SCAN_SUGGESTION)

                    val body = payload(list(token))

                    body.map { it.path("id").asLong() } shouldBe listOf(suggestion.id, notice.id, helpful.id)
                    body.forEach { it.has("type") shouldBe false }
                    val first = body[0]
                    first.path("title").asText() shouldBe "suggestion"
                    first.path("body").asText() shouldBe "본문 suggestion"
                    first.path("receivedAt").isNumber shouldBe true
                    first.path("receivedAt").asLong() shouldBe epochMillisOf(createdAtOf(suggestion.id))
                }
            }

            `when`("다른 회원의 알림이 함께 있으면") {
                then("본인 알림만 온다") {
                    val (aId, aToken) = login("inbox-a")
                    val (bId, _) = login("inbox-b")
                    val mine = seed(aId, "mine")
                    seed(bId, "theirs")

                    val body = payload(list(aToken))

                    body.map { it.path("id").asLong() } shouldBe listOf(mine.id)
                }
            }

            `when`("읽은 알림과 안 읽은 알림이 섞여 있으면") {
                then("읽음 여부가 각각 표시되고 정렬은 시간 역순 그대로다") {
                    val (memberId, token) = login("inbox-read-mix")
                    val readOne = seed(memberId, "read").apply { markRead(LocalDateTime.now()) }
                    notificationRepository.save(readOne)
                    val unread = seed(memberId, "unread")

                    val body = payload(list(token))

                    body.map { it.path("id").asLong() } shouldBe listOf(unread.id, readOne.id)
                    body[0].path("read").asBoolean() shouldBe false
                    body[1].path("read").asBoolean() shouldBe true
                }
            }

            `when`("같은 회원의 다른 기기 알림이 함께 있으면") {
                then("요청 기기(X-Installation-Id)의 알림만 온다") {
                    val (memberId, token) = login("inbox-other-device")
                    seed(memberId, "이 기기")
                    seed(memberId, "다른 기기", installationId = "other-installation")

                    val items = payload(list(token))
                    items.size() shouldBe 1
                    items[0].path("title").asText() shouldBe "이 기기"
                }
            }

            `when`("X-Installation-Id 없이 조회하면") {
                then("400 으로 거절된다") {
                    val (_, token) = login("inbox-no-installation")
                    list(token, installationId = null).status shouldBe 400
                }
            }

            `when`("인증 없이 조회하면") {
                then("401 로 거절된다") {
                    list(null).status shouldBe 401
                }
            }
        }

        given("알림 읽음 처리") {
            `when`("안 읽은 본인 알림을 읽음 처리하면") {
                then("읽음으로 바뀌고 목록에도 반영된다") {
                    val (memberId, token) = login("read-first")
                    val notification = seed(memberId, "target")

                    val body = payload(read(token, notification.id))

                    body.path("id").asLong() shouldBe notification.id
                    body.path("read").asBoolean() shouldBe true
                    payload(list(token))[0].path("read").asBoolean() shouldBe true
                }
            }

            `when`("이미 읽은 알림을 다시 읽음 처리하면") {
                then("성공하고 최초 읽은 시각이 유지된다") {
                    val (memberId, token) = login("read-twice")
                    val notification = seed(memberId, "target")
                    payload(read(token, notification.id))
                    val firstReadAt = readAtOf(notification.id)

                    val body = payload(read(token, notification.id))

                    body.path("read").asBoolean() shouldBe true
                    readAtOf(notification.id) shouldBe firstReadAt
                }
            }

            `when`("다른 회원의 알림을 읽음 처리하면") {
                then("404 NOTIFICATION-002 이고 상대 알림은 그대로다") {
                    val (_, aToken) = login("read-a")
                    val (bId, _) = login("read-b")
                    val theirs = seed(bId, "theirs")

                    val response = read(aToken, theirs.id)

                    response.status shouldBe 404
                    codeOf(response) shouldBe "NOTIFICATION-002"
                    readAtOf(theirs.id) shouldBe null
                }
            }

            `when`("같은 회원의 다른 기기 알림을 읽음 처리하면") {
                then("404 NOTIFICATION-002 이고 그 알림은 그대로다") {
                    val (memberId, token) = login("read-other-device")
                    val other = seed(memberId, "다른 기기", installationId = "other-installation")

                    val response = read(token, other.id)
                    response.status shouldBe 404
                    codeOf(response) shouldBe "NOTIFICATION-002"
                    readAtOf(other.id) shouldBe null
                }
            }

            `when`("존재하지 않는 알림을 읽음 처리하면") {
                then("404 NOTIFICATION-002 다") {
                    val (_, token) = login("read-missing")

                    val response = read(token, 999_999L)

                    response.status shouldBe 404
                    codeOf(response) shouldBe "NOTIFICATION-002"
                }
            }

            `when`("7일이 지난 본인 알림을 읽음 처리하면") {
                then("기간 제한 없이 성공한다") {
                    val (memberId, token) = login("read-old")
                    val old = seed(memberId, "old")
                    setCreatedAt(old.id, LocalDateTime.now().minusDays(8))

                    payload(read(token, old.id)).path("read").asBoolean() shouldBe true
                }
            }

            `when`("인증 없이 읽음 처리하면") {
                then("401 로 거절된다") {
                    read(null, 1L).status shouldBe 401
                }
            }
        }
    }
}

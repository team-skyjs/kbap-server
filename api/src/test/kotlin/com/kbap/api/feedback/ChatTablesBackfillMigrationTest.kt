package com.kbap.api.feedback

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import javax.sql.DataSource

@IntegrationTest
class ChatTablesBackfillMigrationTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val backfillStatements = ClassPathResource("db/migration/V2026.11.05.13.39.50__chat_tables.sql").inputStream.bufferedReader().readText()
            .lines().filterNot { it.trim().startsWith("--") }.joinToString("\n")
            .split(";").map { it.trim() }
            .filter { it.startsWith("INSERT") || it.startsWith("UPDATE") }

        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun rows(sql: String): List<List<String?>> = dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { r ->
                    buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) }
                }
            }
        }

        fun clearChatAndFeedback() = listOf("chat_message", "chat_conversation", "feedback_reply", "feedback").forEach { execute("DELETE FROM $it") }

        beforeEach {
            TestTables.clearAll(dataSource)
            clearChatAndFeedback()
        }
        afterSpec {
            clearChatAndFeedback()
            TestTables.clearAll(dataSource)
        }

        given("지난 문의를 채팅 대화방 이력으로 복사하는 마이그레이션 — S-971") {
            `when`("회원 문의·게스트 문의와 운영진 답변이 있으면") {
                then("회원·게스트 기기마다 방 하나, 문의는 USER·답변은 OPERATOR 메시지로 시간순 복사되고 읽음·마지막 시각이 채워진다") {
                    execute("INSERT INTO member (id, provider, provider_uid, member_status, onboarding_completed, avoidance_substance_codes, status, created_at, updated_at) VALUES (97101, 'GOOGLE', 'chat-97101', 'ACTIVE', 1, '[]', 'ACTIVE', NOW(6), NOW(6))")
                    execute("INSERT IGNORE INTO admin_account (id, admin_id, admin_pwd) VALUES (97101, 'chat-admin', 'x')")
                    execute("INSERT INTO feedback (id, member_id, installation_id, content, image_refs, feedback_status, status, created_at, updated_at) VALUES (97101, 97101, 'inst-member', '회원 문의', '[\"feedback/a.webp\"]', 'ANSWERED', 'ACTIVE', '2026-10-01 10:00:00', NOW(6))")
                    execute("INSERT INTO feedback_reply (id, feedback_id, admin_account_id, content, status, created_at, updated_at) VALUES (97101, 97101, 97101, '답변', 'ACTIVE', '2026-10-01 11:00:00', NOW(6))")
                    execute("INSERT INTO feedback (id, member_id, installation_id, content, feedback_status, status, created_at, updated_at) VALUES (97102, NULL, 'inst-guest', '게스트 첫 문의', 'OPEN', 'ACTIVE', '2026-10-02 10:00:00', NOW(6))")
                    execute("INSERT INTO feedback (id, member_id, installation_id, content, device_info, feedback_status, status, created_at, updated_at) VALUES (97103, NULL, 'inst-guest', '게스트 둘째 문의', '{\"lang\":\"ja\",\"locale\":\"ja-JP\"}', 'OPEN', 'ACTIVE', '2026-10-03 10:00:00', NOW(6))")
                    execute("INSERT INTO feedback (id, member_id, installation_id, content, feedback_status, status, created_at, updated_at) VALUES (97104, NULL, 'inst-deleted', '지운 문의', 'OPEN', 'DELETED', '2026-10-04 10:00:00', NOW(6))")

                    backfillStatements.forEach(::execute)

                    rows("SELECT member_id, guest_installation_id FROM chat_conversation ORDER BY id") shouldBe listOf(listOf("97101", null), listOf(null, "inst-guest"))
                    rows(
                        "SELECT c.guest_installation_id, m.author_role, m.content, m.admin_account_id, m.legacy_feedback_id, m.legacy_reply_id, m.image_refs " +
                            "FROM chat_message m JOIN chat_conversation c ON c.id = m.conversation_id ORDER BY m.id",
                    ) shouldBe listOf(
                        listOf(null, "USER", "회원 문의", null, "97101", null, "[\"feedback/a.webp\"]"),
                        listOf(null, "OPERATOR", "답변", "97101", "97101", "97101", null),
                        listOf("inst-guest", "USER", "게스트 첫 문의", null, "97102", null, null),
                        listOf("inst-guest", "USER", "게스트 둘째 문의", null, "97103", null, null),
                    )
                    rows(
                        "SELECT c.user_last_read_message_id = MAX(m.id), c.operator_last_read_message_id = MAX(m.id), c.last_message_at, c.last_user_message_at " +
                            "FROM chat_conversation c JOIN chat_message m ON m.conversation_id = c.id GROUP BY c.id ORDER BY c.id",
                    ) shouldBe listOf(
                        listOf("1", "1", "2026-10-01 11:00:00.000000", "2026-10-01 10:00:00.000000"),
                        listOf("1", "1", "2026-10-03 10:00:00.000000", "2026-10-03 10:00:00.000000"),
                    )
                    rows("SELECT JSON_UNQUOTE(JSON_EXTRACT(last_device_info, '$.locale')) FROM chat_conversation ORDER BY id") shouldBe listOf(listOf(null), listOf("ja-JP"))
                }
            }
        }
    }
}

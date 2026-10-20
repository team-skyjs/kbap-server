package com.kbap.api.scan

import com.kbap.api.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import javax.sql.DataSource

@IntegrationTest
class ScanLedgerSchemaTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        given("공유 스캔 원장 선행 스키마") {
            `when`("기존 마이그레이션 뒤 적용하면") {
                then("모든 신규 테이블이 존재하고 기존 경로와 신규 모드 OFF가 유지된다") {
                    dataSource.connection.use { c ->
                        val tables = listOf(
                            "scan_runtime_control", "scan_operation", "scan_operation_photo", "scan_attempt",
                            "scan_operation_result", "scan_usage", "scan_merged_item", "scan_occurrence",
                            "scan_history_source", "scan_order", "order_photo", "order_item_source",
                        )
                        val actual = c.createStatement().use { s ->
                            s.executeQuery("SHOW TABLES").use { r -> buildSet { while (r.next()) add(r.getString(1)) } }
                        }
                        tables.forEach { actual shouldContain it }
                        c.createStatement().use { s ->
                            s.executeQuery("SELECT routing_mode, new_modes_enabled, global_call_limit FROM scan_runtime_control WHERE id = 1").use { r ->
                                r.next() shouldBe true
                                r.getString(1) shouldBe "LEGACY"
                                r.getBoolean(2) shouldBe false
                                r.getInt(3) shouldBe 4
                            }
                        }
                    }
                }
            }

            `when`("제어행을 추가로 만들거나 고정 키를 바꾸면") {
                then("두 번째 제어행과 다른 고정 키를 거절한다") {
                    withRollback { c ->
                        duplicate { c.execute("INSERT INTO scan_runtime_control (routing_mode) VALUES ('LEGACY')") }
                        shouldThrow<SQLException> {
                            c.execute("INSERT INTO scan_runtime_control (singleton_key, routing_mode) VALUES (2, 'LEGACY')")
                        }.errorCode shouldBe 3819
                        c.count("scan_runtime_control") shouldBe 1L
                    }
                }
            }

            `when`("같은 회원과 생성 키를 다른 모드나 삭제 상태로 재사용하면") {
                then("기존 원장을 우회하지 못하고 다른 키 영역만 구분한다") {
                    withRollback { c ->
                        val member = member(c)
                        val op = operation(c, member, key = "01")
                        duplicate { operation(c, member, key = "01", mode = "FOOD") }
                        c.execute("UPDATE scan_operation SET status = 'DELETED' WHERE id = $op")
                        duplicate { operation(c, member, key = "01") }
                        operation(c, member, key = "01", namespace = "LEGACY_V1")
                        c.count("scan_operation") shouldBe 2L
                    }
                }
            }

            `when`("이미 사용된 작업에 다른 사진 결과가 추가되면") {
                then("결과는 각각 보존하고 사용 기록은 삭제 후에도 최대 하나다") {
                    withRollback { c ->
                        val member = member(c)
                        val op = operation(c, member)
                        val a = photo(c, member, op, 0)
                        val b = photo(c, member, op, 1)
                        val aa = attempt(c, member, op, a, "photo:a:extract")
                        val ab = attempt(c, member, op, b, "photo:b:extract")
                        result(c, op, a, aa)
                        c.execute("INSERT INTO scan_usage (operation_id, recorded_at, entitlement_at_commit) VALUES ($op, NOW(6), 'FREE')")
                        c.count("scan_operation_result") shouldBe 1L
                        result(c, op, b, ab)
                        c.count("scan_operation_result") shouldBe 2L
                        duplicate { c.execute("INSERT INTO scan_usage (operation_id, recorded_at, entitlement_at_commit) VALUES ($op, NOW(6), 'UNLIMITED')") }
                        c.execute("UPDATE scan_usage SET status = 'DELETED' WHERE operation_id = $op")
                        duplicate { c.execute("INSERT INTO scan_usage (operation_id, recorded_at, entitlement_at_commit) VALUES ($op, NOW(6), 'FREE')") }
                        c.count("scan_usage") shouldBe 1L
                    }
                }
            }

            `when`("같은 실행 단위와 시도를 중복 등록하면") {
                then("한 시도만 남고 별도 사진과 revision 실행은 독립적으로 저장된다") {
                    withRollback { c ->
                        val member = member(c)
                        val op = operation(c, member)
                        attempt(c, member, op, null, "label:r1:translate:ja")
                        duplicate { attempt(c, member, op, null, "label:r1:translate:ja") }
                        attempt(c, member, op, null, "label:r2:translate:ja", revision = 2)
                        c.count("scan_attempt") shouldBe 2L
                    }
                }
            }

            `when`("사진 순서와 객체 또는 업로드 참조가 잘못되면") {
                then("동일 그룹 중복과 없는 업로드를 DB에서 거절한다") {
                    withRollback { c ->
                        val member = member(c)
                        val op = operation(c, member)
                        photo(c, member, op, 0)
                        duplicate { photo(c, member, op, 0, suffix = "other") }
                        val error = shouldThrow<SQLException> {
                            c.execute("INSERT INTO scan_operation_photo (operation_id, photo_id, selection_index, uploaded_image_id, object_key, source_fingerprint, byte_size) VALUES ($op, UUID_TO_BIN(UUID()), 2, -1, 'missing', JSON_OBJECT(), 1)")
                        }
                        error.errorCode shouldBe 1452
                    }
                }
            }

            `when`("확정 시도 없이 결과를 저장하려 하면") {
                then("결과의 영속 시도 참조가 보장된다") {
                    withRollback { c ->
                        val member = member(c)
                        val op = operation(c, member)
                        val photo = photo(c, member, op, 0)
                        val error = shouldThrow<SQLException> { result(c, op, photo, -1) }
                        error.errorCode shouldBe 1452
                    }
                }
            }

            `when`("병합 키가 미확정이거나 같으면") {
                then("미확정 항목은 각각 남고 확정된 동일 키는 중복되지 않는다") {
                    withRollback { c ->
                        val op = operation(c, member(c))
                        fun merged(key: String) = c.execute("INSERT INTO scan_merged_item (operation_id, public_id, merge_key, document) VALUES ($op, UUID_TO_BIN(UUID()), $key, JSON_OBJECT())")
                        merged("NULL")
                        merged("NULL")
                        merged("UNHEX(REPEAT('01', 32))")
                        duplicate { merged("UNHEX(REPEAT('01', 32))") }
                        c.count("scan_merged_item") shouldBe 3L
                    }
                }
            }

            `when`("주문 사진이 원장을 참조하는 동안 삭제하려 하면") {
                then("한 그룹 한 주문을 유지하고 연결된 원본을 연쇄 삭제하지 않는다") {
                    withRollback { c ->
                        val member = member(c)
                        val op = operation(c, member)
                        val photo = photo(c, member, op, 0)
                        val order = c.insert("INSERT INTO orders (member_id, image_path, status, created_at, updated_at) VALUES ($member, NULL, 'ACTIVE', NOW(6), NOW(6))")
                        val second = c.insert("INSERT INTO orders (member_id, image_path, status, created_at, updated_at) VALUES ($member, NULL, 'ACTIVE', NOW(6), NOW(6))")
                        fun link(id: Long) = c.execute("INSERT INTO scan_order (operation_id, order_id, request_key, request_hash, result_revision) VALUES ($op, $id, UUID_TO_BIN(UUID()), UNHEX(REPEAT('02', 32)), 1)")
                        link(order)
                        duplicate { link(second) }
                        c.execute("INSERT INTO order_photo (order_id, operation_photo_id, selection_index, object_key) VALUES ($order, $photo, 0, 'scan-schema/0.webp')")
                        shouldThrow<SQLException> { c.execute("DELETE FROM scan_operation_photo WHERE id = $photo") }.errorCode shouldBe 1451
                        shouldThrow<SQLException> { c.execute("DELETE FROM scan_operation WHERE id = $op") }.errorCode shouldBe 1451
                        c.count("order_photo") shouldBe 1L
                    }
                }
            }

            `when`("환율 없는 모드와 처리 기한을 저장하면") {
                then("가짜 환율 없이 결과 보관 기한과 분리되고 예약 조회 인덱스가 존재한다") {
                    withRollback { c ->
                        val op = operation(c, member(c), mode = "LABEL")
                        c.createStatement().use { s ->
                            s.executeQuery("SELECT display_currency, fx_krw_per_unit, fx_rate_date, expires_at < result_expires_at FROM scan_operation WHERE id = $op").use { r ->
                                r.next() shouldBe true
                                r.getObject(1) shouldBe null
                                r.getObject(2) shouldBe null
                                r.getObject(3) shouldBe null
                                r.getBoolean(4) shouldBe true
                            }
                            s.executeQuery("SHOW INDEX FROM scan_operation").use { r ->
                                val names = buildSet { while (r.next()) add(r.getString("Key_name")) }
                                listOf("idx_scan_operation_reservation", "idx_scan_operation_expiry", "idx_scan_operation_result_expiry", "idx_scan_operation_key_retirement").forEach { names shouldContain it }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun withRollback(block: (Connection) -> Unit) {
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                block(c)
            } finally {
                c.rollback()
            }
        }
    }

    private fun member(c: Connection): Long = c.insert(
        "INSERT INTO member (provider, provider_uid, member_status, onboarding_completed, status, created_at, updated_at) VALUES ('GOOGLE', UUID(), 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))",
    )

    private fun operation(c: Connection, member: Long, key: String = "01", namespace: String = "SCAN_CREATE", mode: String = "MENU_BOARD"): Long = c.insert(
        "INSERT INTO scan_operation (member_id, key_namespace, request_key, manifest_hash, mode, initial_language, fx_state, request_document, limits_version, expires_at, result_expires_at, key_retire_after) VALUES ($member, '$namespace', UNHEX(REPEAT('$key', 16)), UNHEX(REPEAT('01', 32)), '$mode', 'ko', 'NOT_APPLICABLE', JSON_OBJECT(), 'scan-v1', NOW(6) + INTERVAL 30 MINUTE, NOW(6) + INTERVAL 7 DAY, NOW(6) + INTERVAL 30 DAY)",
    )

    private fun photo(c: Connection, member: Long, op: Long, index: Int, suffix: String = "$index"): Long {
        val upload = c.insert("INSERT INTO uploaded_image (member_id, object_path, content_type, size_bytes, status, created_at, updated_at) VALUES ($member, CONCAT('scan-schema/', UUID(), '.webp'), 'image/webp', 128, 'ACTIVE', NOW(6), NOW(6))")
        return c.insert("INSERT INTO scan_operation_photo (operation_id, photo_id, selection_index, uploaded_image_id, object_key, source_fingerprint, byte_size) VALUES ($op, UUID_TO_BIN(UUID()), $index, $upload, 'scan-schema/$suffix.webp', JSON_OBJECT('checksum', 'synthetic'), 128)")
    }

    private fun attempt(c: Connection, member: Long, op: Long, photo: Long?, unit: String, revision: Int = 1): Long = c.insert(
        "INSERT INTO scan_attempt (operation_id, member_id, photo_row_id, unit_key, phase, target_manifest_revision, attempt_no, claim_token, reservation_epoch, state, started_at, deadline_at, lease_until) VALUES ($op, $member, ${photo ?: "NULL"}, '$unit', 'EXTRACT', $revision, 1, UUID_TO_BIN(UUID()), 0, 'RUNNING', NOW(6), NOW(6) + INTERVAL 90 SECOND, NOW(6) + INTERVAL 120 SECOND)",
    )

    private fun result(c: Connection, op: Long, photo: Long, attempt: Long) = c.execute(
        "INSERT INTO scan_operation_result (operation_id, photo_row_id, committed_attempt_id, committed_attempt_no, target_manifest_revision, result_document) VALUES ($op, $photo, $attempt, 1, 1, JSON_OBJECT('items', JSON_ARRAY()))",
    )

    private fun duplicate(block: () -> Unit) {
        shouldThrow<SQLException>(block).errorCode shouldBe 1062
    }

    private fun Connection.execute(sql: String) = createStatement().use { it.executeUpdate(sql) }

    private fun Connection.insert(sql: String): Long = createStatement().use { s ->
        s.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS)
        s.generatedKeys.use { r -> r.next(); r.getLong(1) }
    }

    private fun Connection.count(table: String): Long = createStatement().use { s ->
        s.executeQuery("SELECT COUNT(*) FROM $table").use { r -> r.next(); r.getLong(1) }
    }
}

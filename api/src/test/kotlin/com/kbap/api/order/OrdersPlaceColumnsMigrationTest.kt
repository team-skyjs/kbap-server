package com.kbap.api.order

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import javax.sql.DataSource

@IntegrationTest
class OrdersPlaceColumnsMigrationTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val placeColumns = listOf(
            "place_source",
            "place_external_id",
            "place_name",
            "place_address",
            "place_language",
        )

        fun columnMeta(): Map<String, String> {
            val meta = mutableMapOf<String, String>()
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT column_name, is_nullable FROM information_schema.columns " +
                        "WHERE table_schema = DATABASE() AND table_name = 'orders' AND column_name IN " +
                        placeColumns.joinToString(prefix = "(", postfix = ")") { "'$it'" },
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        while (rs.next()) meta[rs.getString(1)] = rs.getString(2)
                    }
                }
            }
            return meta
        }

        beforeContainer { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("orders place 스냅샷 마이그레이션 적용 후") {
            `when`("orders 스키마를 조회하면") {
                then("place 스냅샷 5컬럼이 모두 nullable 로 존재한다") {
                    val meta = columnMeta()
                    meta.keys shouldContainAll placeColumns
                    placeColumns.forEach { meta[it] shouldBe "YES" }
                }
            }

            `when`("place 컬럼을 지정하지 않고 주문 행을 넣으면") {
                then("place 스냅샷 5컬럼이 전부 NULL 로 저장된다(백필 없음·기존 행 무변)") {
                    dataSource.connection.use { c ->
                        c.createStatement().use {
                            it.execute(
                                "INSERT INTO member (id, provider, provider_uid, member_status, onboarding_completed, " +
                                    "status, created_at, updated_at) VALUES (901, 'GOOGLE', 'kb451-uid', 'ACTIVE', 1, " +
                                    "'ACTIVE', NOW(6), NOW(6))",
                            )
                            it.execute(
                                "INSERT INTO orders (member_id, image_path, status, created_at, updated_at) " +
                                    "VALUES (901, 'orders/kb451.webp', 'ACTIVE', NOW(6), NOW(6))",
                            )
                        }
                        var allNull = false
                        c.prepareStatement(
                            "SELECT ${placeColumns.joinToString()} FROM orders WHERE image_path = 'orders/kb451.webp'",
                        ).use { ps ->
                            ps.executeQuery().use { rs ->
                                rs.next()
                                allNull = placeColumns.indices.all { rs.getObject(it + 1) == null }
                            }
                        }
                        allNull shouldBe true
                    }
                }
            }
        }
    }
}

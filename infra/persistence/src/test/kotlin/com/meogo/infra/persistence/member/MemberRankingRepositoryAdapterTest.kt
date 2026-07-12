package com.meogo.infra.persistence.member

import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import javax.sql.DataSource

@SpringBootTest
@Import(MySqlContainerConfig::class)
class MemberRankingRepositoryAdapterTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var adapter: MemberRankingRepositoryAdapter

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        fun clear() {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM member_ranking")
                }
            }
        }

        beforeContainer {
            clear()
        }

        given("스캔 횟수 카운트업") {
            `when`("랭킹 기록이 없는 회원이 처음 스캔하면") {
                then("스캔 횟수가 1이 된다") {
                    adapter.increaseScanCount(11L)

                    adapter.scanCountOf(11L) shouldBe 1
                }
            }

            `when`("같은 회원이 여러 번 스캔하면") {
                then("스캔할 때마다 1씩 늘어난다") {
                    repeat(3) { adapter.increaseScanCount(11L) }

                    adapter.scanCountOf(11L) shouldBe 3
                }
            }

            `when`("다른 회원이 스캔하면") {
                then("서로의 횟수에 영향을 주지 않는다") {
                    adapter.increaseScanCount(11L)
                    adapter.increaseScanCount(11L)
                    adapter.increaseScanCount(99L)

                    adapter.scanCountOf(11L) shouldBe 2
                    adapter.scanCountOf(99L) shouldBe 1
                }
            }
        }

        given("스캔 횟수 조회") {
            `when`("한 번도 스캔하지 않은 회원이면") {
                then("0을 반환한다") {
                    adapter.scanCountOf(12345L) shouldBe 0
                }
            }
        }
    }
}

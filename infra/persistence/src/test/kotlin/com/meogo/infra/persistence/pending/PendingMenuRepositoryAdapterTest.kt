package com.meogo.infra.persistence.pending

import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class)
class PendingMenuRepositoryAdapterTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var adapter: PendingMenuRepositoryAdapter

    @Autowired
    private lateinit var jpaRepository: PendingMenuJpaRepository

    init {
        given("PendingMenu 대기열 어댑터") {
            `when`("새 표준명을 등록하면") {
                then("PENDING 상태로 1건 적재된다") {
                    jpaRepository.deleteAll()

                    adapter.enqueue("우주라면")

                    val all = jpaRepository.findAll()
                    all.size shouldBe 1
                    all.first().standardName shouldBe "우주라면"
                    all.first().queueStatus shouldBe "PENDING"
                }
            }

            `when`("같은 표준명을 두 번 등록하면") {
                then("중복 없이 1건만 유지된다(unique dedup)") {
                    jpaRepository.deleteAll()

                    adapter.enqueue("마라샹궈")
                    adapter.enqueue("마라샹궈")

                    jpaRepository.count() shouldBe 1
                }
            }
        }
    }
}

package com.kbap.api.infra.redis

import com.kbap.api.IntegrationTest
import com.kbap.common.port.quota.InstallationQuotaStore
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.util.UUID

@IntegrationTest
class RedisInstallationQuotaStoreTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var store: InstallationQuotaStore

    init {
        given("카운터 키가 없는 기기의 한도 획득") {
            `when`("이미 기록된 1건이 윈도 끝에 걸쳐 있고 한도가 1이면") {
                then("그 1건이 윈도를 벗어나기 전엔 거절하고, 벗어난 뒤엔 내준다 — 시드는 기록 시각 그대로 만료된다") {
                    val installation = "seed-${UUID.randomUUID()}"
                    val window = Duration.ofSeconds(4)
                    val recordedAt = System.currentTimeMillis() - 3_000

                    store.tryAcquire("quota-test", installation, "first", 1, window, listOf(recordedAt)) shouldBe false
                    Thread.sleep(1_500)
                    store.tryAcquire("quota-test", installation, "second", 1, window, listOf(recordedAt)) shouldBe true
                }
            }

            `when`("키가 이미 있으면") {
                then("기록 시각 목록을 다시 넣지 않는다 — 같은 기록을 두 번 세지 않는다") {
                    val installation = "seed-${UUID.randomUUID()}"
                    val window = Duration.ofMinutes(1)
                    val recordedAt = System.currentTimeMillis() - 1_000

                    store.tryAcquire("quota-test", installation, "first", 3, window, listOf(recordedAt)) shouldBe true
                    store.tryAcquire("quota-test", installation, "second", 3, window, listOf(recordedAt, recordedAt)) shouldBe true
                    store.tryAcquire("quota-test", installation, "third", 3, window, emptyList()) shouldBe false
                }
            }
        }
    }
}

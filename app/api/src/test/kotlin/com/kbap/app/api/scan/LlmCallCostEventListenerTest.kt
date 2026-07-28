package com.kbap.app.api.scan

import com.kbap.common.core.llm.LlmCallCostIncurred
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.domain.metering.LlmCallCostService
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import java.math.BigDecimal
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

@SpringBootTest
@Import(MySqlContainerConfig::class)
class LlmCallCostEventListenerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var eventPublisher: ApplicationEventPublisher

    @Autowired
    private lateinit var llmCallCostService: LlmCallCostService

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        fun countByModel(modelName: String): Int =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM llm_call_cost WHERE model_name = ?",
                ).use { ps ->
                    ps.setString(1, modelName)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }

        fun clearLedger() {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM llm_call_cost")
                }
            }
        }

        fun ledgerCount(): Int =
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM llm_call_cost").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }

        given("LLM 비용 이벤트가 컨텍스트에 발행됨") {
            `when`("LlmCallCostIncurred 를 발행하면") {
                then("비동기 리스너가 원장에 행을 저장한다") {
                    val modelName = "listener-model-${UUID.randomUUID()}"

                    eventPublisher.publishEvent(
                        LlmCallCostIncurred(
                            modelName = modelName,
                            inputTokens = 100,
                            outputTokens = 50,
                            costUsd = BigDecimal("0.000123"),
                            costKrw = BigDecimal("0.18"),
                        ),
                    )

                    eventually(5.seconds) {
                        countByModel(modelName) shouldBe 1
                    }
                }
            }
        }

        given("비용 기록 저장이 실패하는 상황") {
            `when`("리스너가 저장 실패하는 이벤트를 처리하면") {
                then("예외가 전파되지 않고 스캔 흐름에 영향을 주지 않는다") {
                    clearLedger()
                    val listener = LlmCallCostEventListener(llmCallCostService)
                    val overflowingModelName = "x".repeat(101)
                    val event = LlmCallCostIncurred(
                        modelName = overflowingModelName,
                        inputTokens = 100,
                        outputTokens = 50,
                        costUsd = BigDecimal("0.000123"),
                        costKrw = BigDecimal("0.18"),
                    )

                    shouldNotThrowAny { listener.handle(event) }

                    ledgerCount() shouldBe 0
                }
            }
        }
    }
}

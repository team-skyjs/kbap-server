package com.kbap.domain.metering

import com.kbap.common.core.llm.LlmCallCostIncurred
import com.kbap.common.core.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.math.BigDecimal
import javax.sql.DataSource

@SpringBootTest
@Import(MySqlContainerConfig::class)
class LlmCallCostServiceTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var service: LlmCallCostService

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        fun clearLedger() {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM llm_call_cost")
                }
            }
        }

        fun countLedger(): Int =
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM llm_call_cost").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }

        fun costRowOf(modelName: String): Triple<Long, BigDecimal, BigDecimal>? =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT input_tokens, cost_usd, cost_krw FROM llm_call_cost WHERE model_name = ?",
                ).use { ps ->
                    ps.setString(1, modelName)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            Triple(rs.getLong("input_tokens"), rs.getBigDecimal("cost_usd"), rs.getBigDecimal("cost_krw"))
                        } else {
                            null
                        }
                    }
                }
            }

        beforeContainer {
            clearLedger()
        }

        given("LLM 호출 비용 기록") {
            `when`("record 로 비용 이벤트를 기록하면") {
                then("원장에 1행이 저장된다") {
                    service.record(
                        LlmCallCostIncurred(
                            modelName = "gpt-4o-mini",
                            inputTokens = 1000,
                            outputTokens = 500,
                            costUsd = BigDecimal("0.000450"),
                            costKrw = BigDecimal("0.68"),
                        ),
                    )

                    countLedger() shouldBe 1
                }
            }

            `when`("USD 6자리·KRW 2자리 비용을 기록하면") {
                then("DECIMAL 정밀도가 왕복 보존된다") {
                    service.record(
                        LlmCallCostIncurred(
                            modelName = "gpt-4o-mini-2024-07-18",
                            inputTokens = 1237,
                            outputTokens = 567,
                            costUsd = BigDecimal("0.000526"),
                            costKrw = BigDecimal("0.79"),
                        ),
                    )

                    val row = costRowOf("gpt-4o-mini-2024-07-18").shouldNotBeNull()
                    row.first shouldBe 1237L
                    row.second shouldBe BigDecimal("0.000526")
                    row.third shouldBe BigDecimal("0.79")
                }
            }
        }
    }
}

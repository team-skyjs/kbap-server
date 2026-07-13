package com.meogo.app.batch.scoring

import com.meogo.domain.avoidance.AvoidanceSubstance
import com.meogo.domain.avoidance.AvoidanceSubstanceCode
import com.meogo.domain.avoidance.AvoidanceSubstanceRepository
import com.meogo.domain.food.Food
import com.meogo.domain.food.FoodScoringSource
import com.meogo.infra.llm.client.LlmFanoutClient
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.concurrent.Executors
import java.util.function.Supplier

class ScoringJobConfigGatingTest : BehaviorSpec({

    val contextRunner = ApplicationContextRunner()
        .withBean(FoodScoringSource::class.java, Supplier { emptyFoodScoringSource() })
        .withBean(LlmFanoutClient::class.java, Supplier { emptyLlmFanoutClient() })
        .withBean(AvoidanceSubstanceRepository::class.java, Supplier { emptyAvoidanceSubstanceRepository() })
        .withUserConfiguration(ScoringJobConfig::class.java)

    given("ScoringJobConfig 와 잡 협력자 스텁 빈으로 구성한 배치 컨텍스트") {
        `when`("meogo.scoring.runner.enabled 프로퍼티를 설정하지 않고 컨텍스트를 올리면") {
            then("ScoringJobRunner 빈이 등록되지 않는다(부팅 시 잡 미자동실행)") {
                contextRunner.run { context ->
                    context.getBeanNamesForType(ScoringJobRunner::class.java).size shouldBe 0
                }
            }
        }

        `when`("meogo.scoring.runner.enabled=true 로 컨텍스트를 올리면") {
            then("ScoringJobRunner 빈이 등록된다(부팅 시 잡 자동실행)") {
                contextRunner
                    .withPropertyValues("meogo.scoring.runner.enabled=true")
                    .run { context ->
                        context.getBeanNamesForType(ScoringJobRunner::class.java).size shouldBe 1
                    }
            }
        }

        `when`("meogo.scoring.runner.enabled=false 로 컨텍스트를 올리면") {
            then("ScoringJobRunner 빈이 등록되지 않는다") {
                contextRunner
                    .withPropertyValues("meogo.scoring.runner.enabled=false")
                    .run { context ->
                        context.getBeanNamesForType(ScoringJobRunner::class.java).size shouldBe 0
                    }
            }
        }
    }
})

private fun emptyFoodScoringSource(): FoodScoringSource =
    object : FoodScoringSource {
        override fun nextChunk(page: Int, size: Int): List<Food> = emptyList()
    }

private fun emptyLlmFanoutClient(): LlmFanoutClient =
    LlmFanoutClient(emptyList(), Executors.newVirtualThreadPerTaskExecutor())

private fun emptyAvoidanceSubstanceRepository(): AvoidanceSubstanceRepository =
    object : AvoidanceSubstanceRepository {
        override fun findByCodes(codes: Set<AvoidanceSubstanceCode>): List<AvoidanceSubstance> = emptyList()
    }

package com.kbap.infra.llm.config

import com.kbap.infra.llm.embedding.SpringAiTextEmbeddingClient
import io.kotest.core.annotation.EnabledCondition
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.bedrock.titan.BedrockTitanEmbeddingModel
import org.springframework.ai.bedrock.titan.api.TitanEmbeddingBedrockApi
import java.time.Duration
import kotlin.reflect.KClass

class EmbeddingSmokeEnabledCondition : EnabledCondition {
    override fun enabled(kclass: KClass<out Spec>): Boolean =
        System.getProperty(SMOKE_ENABLED_PROPERTY) == "true"

    companion object {
        const val SMOKE_ENABLED_PROPERTY = "embedding.smoke.enabled"
    }
}

@EnabledIf(EmbeddingSmokeEnabledCondition::class)
class EmbeddingSmokeTest : BehaviorSpec({

    given("AWS 기본 자격증명 체인으로 Bedrock Titan V2 를 직접 구성(수동 실행: -Dembedding.smoke.enabled=true, quickstart 참조)") {
        val api = TitanEmbeddingBedrockApi(
            TitanEmbeddingBedrockApi.TitanEmbeddingModel.TITAN_EMBED_TEXT_V2.id(),
            System.getenv("AWS_REGION") ?: "ap-northeast-2",
            Duration.ofSeconds(30),
        )
        val client = SpringAiTextEmbeddingClient(BedrockTitanEmbeddingModel(api, ObservationRegistry.NOOP), 1024)

        `when`("한국어 음식명 텍스트 1건을 실호출로 embed 하면") {
            then("1024차원 벡터 1건을 받는다") {
                val vectors = client.embed(listOf("김치찌개 | 돼지고기와 김치를 끓인 찌개"))

                vectors shouldHaveSize 1
                vectors.single().size shouldBe 1024
            }
        }
    }
})

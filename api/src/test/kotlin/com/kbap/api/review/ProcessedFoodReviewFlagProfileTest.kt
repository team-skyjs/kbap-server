package com.kbap.api.review

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.FileSystemResource

class ProcessedFoodReviewFlagProfileTest : BehaviorSpec({
    fun flagOf(file: String): String? {
        val raw = YamlPropertiesFactoryBean().apply { setResources(FileSystemResource("src/main/resources/$file")) }.`object`!!
            .getProperty("kbap.review.processed-food-write-enabled") ?: return null
        return Regex("""^\$\{[^:}]+:([^}]*)}$""").find(raw)?.groupValues?.get(1) ?: raw
    }

    given("가공식품 리뷰 쓰기 플래그의 프로필별 기본값") {
        `when`("환경변수 없이 뜨면") {
            then("기본·운영·스테이징·로컬은 꺼져 있고 dev 만 켜져 있다(앱 2차 QA)") {
                flagOf("application.yml") shouldBe "false"
                flagOf("application-dev.yml") shouldBe "true"
                listOf("application-prod.yml", "application-staging.yml", "application-local.yml").forEach { flagOf(it) shouldBe null }
            }
        }
    }
})

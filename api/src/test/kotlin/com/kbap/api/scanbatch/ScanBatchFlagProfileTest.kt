package com.kbap.api.scanbatch

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.FileSystemResource

class ScanBatchFlagProfileTest : BehaviorSpec({
    fun flagOf(file: String, key: String): String? {
        val raw = YamlPropertiesFactoryBean().apply { setResources(FileSystemResource("src/main/resources/$file")) }.`object`!!.getProperty(key) ?: return null
        return Regex("""^\$\{[^:}]+:([^}]*)}$""").find(raw)?.groupValues?.get(1) ?: raw
    }

    given("여러 장 스캔 기능 플래그의 프로필별 기본값") {
        `when`("환경변수 없이 뜨면") {
            then("기본·운영·스테이징·로컬은 꺼져 있고 dev 만 켜져 있다(QA), 실행 스위치는 기본 켜짐") {
                flagOf("application.yml", "kbap.scan.batch-enabled") shouldBe "false"
                flagOf("application-dev.yml", "kbap.scan.batch-enabled") shouldBe "true"
                listOf("application-prod.yml", "application-staging.yml", "application-local.yml").forEach { flagOf(it, "kbap.scan.batch-enabled") shouldBe null }
                flagOf("application.yml", "kbap.scan.batch-execution-enabled") shouldBe "true"
            }
        }
    }
})

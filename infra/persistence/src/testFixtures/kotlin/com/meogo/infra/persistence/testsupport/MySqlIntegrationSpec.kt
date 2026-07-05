package com.meogo.infra.persistence.testsupport

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class)
abstract class MySqlIntegrationSpec : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)
}

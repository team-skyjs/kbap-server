package com.kbap.api

import com.kbap.api.auth.FakeSocialTokenVerifierConfig
import com.kbap.api.place.FakePlaceSearchConfig
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.core.testsupport.RedisContainerConfig
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import(
    MySqlContainerConfig::class,
    RedisContainerConfig::class,
    FakeSocialTokenVerifierConfig::class,
    FakePlaceSearchConfig::class,
)
annotation class IntegrationTest

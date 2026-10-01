package com.kbap.api.core.config

import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class JpaConnectionConfig {
    @Bean
    fun releaseConnectionAfterTransaction(): HibernatePropertiesCustomizer =
        HibernatePropertiesCustomizer { it["hibernate.connection.handling_mode"] = "DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION" }
}

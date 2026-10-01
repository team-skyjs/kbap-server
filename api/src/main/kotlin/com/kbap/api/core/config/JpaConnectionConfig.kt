package com.kbap.api.core.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class JpaConnectionConfig {
    @Bean
    @ConditionalOnProperty(name = ["kbap.jpa.release-connection-after-transaction"], havingValue = "true", matchIfMissing = true)
    fun releaseConnectionAfterTransaction(): HibernatePropertiesCustomizer =
        HibernatePropertiesCustomizer { it["hibernate.connection.handling_mode"] = "DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION" }
}

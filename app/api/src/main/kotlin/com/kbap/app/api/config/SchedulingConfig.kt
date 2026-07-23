package com.kbap.app.api.config

import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import javax.sql.DataSource

// 운영 api 2대 전제 — 모든 @Scheduled 는 ShedLock 으로 감싸 1대만 실행한다(KB-226).
@Configuration
@EnableScheduling
class SchedulingConfig {
    @Bean
    fun lockProvider(dataSource: DataSource): JdbcTemplateLockProvider =
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(JdbcTemplate(dataSource))
                // DB 서버 시각 기준 — 인스턴스 간 시계 편차에 흔들리지 않는다.
                .usingDbTime()
                .build(),
        )
}

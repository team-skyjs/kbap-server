package com.kbap.app.api.scan

import com.kbap.core.scan.InterpretedName
import com.kbap.core.scan.ScannedNameInterpreter
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class UnavailableScannedNameInterpreterConfig {

    @Bean
    @ConditionalOnMissingBean(ScannedNameInterpreter::class)
    fun unavailableScannedNameInterpreter(): ScannedNameInterpreter =
        object : ScannedNameInterpreter {
            override fun interpret(texts: List<String>): List<InterpretedName> =
                throw IllegalStateException("테스트 환경에서는 정제 서비스를 호출하지 않습니다")
        }
}

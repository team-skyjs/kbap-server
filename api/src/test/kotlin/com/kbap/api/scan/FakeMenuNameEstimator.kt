package com.kbap.api.scan

import com.kbap.common.port.llm.MenuNameEstimation
import com.kbap.common.port.llm.MenuNameEstimator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.util.concurrent.CopyOnWriteArrayList

class FakeMenuNameEstimator : MenuNameEstimator {
    val calls: MutableList<List<String>> = CopyOnWriteArrayList()

    @Volatile
    var answer: (List<String>) -> List<MenuNameEstimation> = { emptyList() }

    fun reset() {
        calls.clear()
        answer = { emptyList() }
    }

    override fun estimate(koreanNames: List<String>): List<MenuNameEstimation> {
        calls += koreanNames
        return answer(koreanNames)
    }
}

@Configuration
class FakeMenuNameEstimatorConfig {
    @Bean
    @Primary
    fun fakeMenuNameEstimator(): FakeMenuNameEstimator = FakeMenuNameEstimator()
}

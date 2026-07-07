package com.meogo.app.api.config
import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
import org.springframework.context.annotation.Import

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.options

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class CorsConfigTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    init {
        given("CORS 프리플라이트 요청") {
            `when`("다른 Origin 에서 /api 경로로 GET 프리플라이트를 보내면") {
                then("요청 Origin 을 반사한 Access-Control-Allow-Origin 과 credentials 허용 헤더를 응답한다") {
                    mockMvc.options("/api/v1/foods/1") {
                        header("Origin", "http://localhost:3000")
                        header("Access-Control-Request-Method", "GET")
                    }.andExpect {
                        status { isOk() }
                        header { string("Access-Control-Allow-Origin", "http://localhost:3000") }
                        header { string("Access-Control-Allow-Credentials", "true") }
                    }
                }
            }
        }
    }
}

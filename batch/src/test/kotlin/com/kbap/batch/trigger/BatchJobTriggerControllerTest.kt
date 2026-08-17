package com.kbap.batch.trigger

import com.kbap.common.core.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.hamcrest.Matchers.containsString
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class BatchJobTriggerControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    init {
        given("상시 기동된 배치 애플리케이션") {
            `when`("등록되지 않은 잡 이름으로 트리거하면") {
                then("404 와 실행 가능한 잡 목록을 반환한다") {
                    mockMvc.post("/internal/batch/jobs/unknownJob")
                        .andExpect {
                            status { isNotFound() }
                            jsonPath("$.status") { value("NOT_FOUND") }
                            jsonPath("$.message") { value(containsString("foodContentOutboxPublishJob")) }
                        }
                }
            }

            `when`("등록된 잡 이름으로 트리거하면") {
                then("잡이 끝날 때까지 기다린 뒤 실행 결과를 반환한다") {
                    mockMvc.post("/internal/batch/jobs/foodContentOutboxPublishJob")
                        .andExpect {
                            status { isOk() }
                            jsonPath("$.jobName") { value("foodContentOutboxPublishJob") }
                            jsonPath("$.status") { value("COMPLETED") }
                            jsonPath("$.exitCode") { value("COMPLETED") }
                        }
                }
            }

            `when`("앞선 실행이 끝난 뒤 다시 트리거하면") {
                then("잡이 다시 실행된다") {
                    mockMvc.post("/internal/batch/jobs/foodContentOutboxPublishJob")
                        .andExpect { status { isOk() } }
                    mockMvc.post("/internal/batch/jobs/foodContentOutboxPublishJob")
                        .andExpect {
                            status { isOk() }
                            jsonPath("$.status") { value("COMPLETED") }
                        }
                }
            }
        }
    }
}

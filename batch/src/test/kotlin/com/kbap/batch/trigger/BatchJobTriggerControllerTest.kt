package com.kbap.batch.trigger

import com.kbap.batch.BatchIntegrationTest
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.hamcrest.Matchers.containsString
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@BatchIntegrationTest
class BatchJobTriggerControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    init {
        given("상시 기동된 배치 애플리케이션") {
            `when`("등록되지 않은 잡 이름으로 트리거하면") {
                then("404 와 실행 가능한 잡 목록을 반환한다") {
                    mockMvc.post("/internal/batch/jobs?jobName=unknownJob")
                        .andExpect {
                            status { isNotFound() }
                            jsonPath("$.status") { value("NOT_FOUND") }
                            jsonPath("$.message") { value(containsString("foodContentOutboxPublishJob")) }
                        }
                }
            }

            `when`("등록된 잡 이름으로 트리거하면") {
                then("잡을 기다리지 않고 202 와 executionId 를 즉시 반환한다") {
                    val executionId = trigger("foodContentOutboxPublishJob")

                    executionId shouldNotBe null
                    awaitStatus(executionId) shouldBe "COMPLETED"
                }
            }

            `when`("완료된 실행을 조회하면") {
                then("최종 상태와 종료 코드를 반환한다") {
                    val executionId = trigger("foodContentOutboxPublishJob")
                    awaitStatus(executionId)

                    mockMvc.get("/internal/batch/executions/$executionId")
                        .andExpect {
                            status { isOk() }
                            jsonPath("$.jobName") { value("foodContentOutboxPublishJob") }
                            jsonPath("$.status") { value("COMPLETED") }
                            jsonPath("$.exitCode") { value("COMPLETED") }
                        }
                }
            }

            `when`("존재하지 않는 실행 id 를 조회하면") {
                then("404 를 반환한다") {
                    mockMvc.get("/internal/batch/executions/99999999")
                        .andExpect {
                            status { isNotFound() }
                            jsonPath("$.status") { value("NOT_FOUND") }
                        }
                }
            }

            `when`("실행 중인 잡을 다시 트리거하면") {
                then("409 와 실행 중인 executionId 를 반환한다") {
                    val runningId = trigger(SlowJobTestConfig.JOB_NAME)

                    mockMvc.post("/internal/batch/jobs?jobName=${SlowJobTestConfig.JOB_NAME}")
                        .andExpect {
                            status { isConflict() }
                            jsonPath("$.status") { value("ALREADY_RUNNING") }
                            jsonPath("$.executionId") { value(runningId.toInt()) }
                        }

                    awaitStatus(runningId) shouldBe "COMPLETED"
                }
            }
        }
    }

    private fun trigger(jobName: String): Long {
        val body = mockMvc.post("/internal/batch/jobs?jobName=$jobName")
            .andExpect { status { isAccepted() } }
            .andReturn().response.contentAsString
        return com.jayway.jsonpath.JsonPath.read<Int>(body, "$.executionId").toLong()
    }

    private fun awaitStatus(executionId: Long): String {
        repeat(100) {
            val body = mockMvc.get("/internal/batch/executions/$executionId")
                .andReturn().response.contentAsString
            val status = com.jayway.jsonpath.JsonPath.read<String>(body, "$.status")
            if (status != "STARTING" && status != "STARTED") return status
            Thread.sleep(100)
        }
        return "TIMEOUT"
    }
}
